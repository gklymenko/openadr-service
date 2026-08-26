package com.qcharge.openadr.service.report;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.service.report.model.ReportSchedule;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.utility.RequestUtils;
import com.qcharge.openadr.utility.TimeRange;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/** Coordinates transport I/O between short persisted delivery-lease transactions. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportDeliveryCoordinator {

    private final ReportRequestStore requestStore;
    private final TelemetryReportFactory telemetryReportFactory;
    private final ReportService reportService;
    private final VtnTransportService transportService;
    private final OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;
    private final Clock clock;

    public void deliverOneShot(
            ReportRequest request,
            OpenAdrSessionSnapshot session
    ) {
        var claimed = requestStore.claimActive(
                request.getReportRequestId(),
                clock.instant()
        );
        if (claimed.isEmpty()) {
            return;
        }
        ReportRequestStore.DeliveryClaim claim = claimed.orElseThrow();
        ReportRequest claimedRequest = claim.request();
        OadrCancelReportType cancellation = null;
        try {
            if (isMetadata(claimedRequest)) {
                sendMetadata(claimedRequest, session);
            } else {
                cancellation = sendTelemetry(
                        claimedRequest,
                        telemetryReportFactory.oneShot(claimedRequest),
                        session
                );
            }
            requestStore.completeDelivery(claim);
        } catch (RuntimeException exception) {
            requestStore.releaseDelivery(claim);
            throw exception;
        }
        if (cancellation != null) {
            handlePiggybackCancellation(cancellation, session);
        }
    }

    public void deliverDueReports() {
        Instant now = clock.instant();
        OpenAdrSessionSnapshot session = lifecycleCoordinator.requireRegisteredSession();
        requestStore.findFinalReportsPending().forEach(request -> deliverFinal(request, session));
        requestStore.findDue(now).forEach(request -> deliverDue(request, session, now));
    }

    public void handleStandaloneCancellation(
            OadrCancelReportType cancellation,
            OpenAdrSessionSnapshot session
    ) {
        ReportRequestStore.CancellationBatch batch = beginCancellation(cancellation);
        var responseBuilder = Oadr20bEiReportBuilders
                .newOadr20bCanceledReportBuilder(
                        cancellation.getRequestID(),
                        batch.accepted() ? OpenADRResponseCode.OK : OpenADRResponseCode.INVALID_ID,
                        session.venId()
                );
        requestStore.findAllPendingReportRequestIds()
                .forEach(responseBuilder::addPendingReportRequestId);
        if (!batch.accepted()) {
            responseBuilder.withResponseDescription(
                    "Unknown or non-cancellable reportRequestID: "
                            + batch.invalidReportRequestIds()
            );
        }

        OadrCanceledReportType response = responseBuilder.build();
        transportService.send(OpenAdrOperations.CANCELED_REPORT_RESPONSE, response, session);

        deliverFinalReportsIfRequested(cancellation, batch, session);
    }

    private void deliverDue(
            ReportRequest request,
            OpenAdrSessionSnapshot session,
            Instant now
    ) {
        var claimed = requestStore.claimDue(request.getReportRequestId(), now);
        if (claimed.isEmpty()) {
            return;
        }
        ReportRequestStore.DeliveryClaim claim = claimed.orElseThrow();
        ReportRequest claimedRequest = claim.request();
        OadrCancelReportType cancellation = null;
        try {
            ReportSchedule schedule = ReportSchedule.restore(claimedRequest);
            Instant dueAt = claimedRequest.getNextReportAt();
            if (dueAt == null || dueAt.isAfter(now)) {
                requestStore.releaseDelivery(claim);
                return;
            }

            Instant deliveredThrough;
            if (isMetadata(claimedRequest)) {
                sendMetadata(claimedRequest, session);
                deliveredThrough = dueAt;
            } else {
                TimeRange window = schedule.deliveryWindow(
                        dueAt,
                        claimedRequest.getLastReportedAt()
                );
                OadrReportType payload = telemetryReportFactory.periodic(
                        claimedRequest,
                        schedule,
                        window,
                        now
                );
                cancellation = sendTelemetry(claimedRequest, payload, session);
                deliveredThrough = window.endExclusive();
            }

            requestStore.recordDelivery(
                    claim,
                    deliveredThrough,
                    schedule.nextDeliveryAfter(deliveredThrough)
            );
        } catch (RuntimeException exception) {
            requestStore.releaseDelivery(claim);
            log.error(
                    "Failed to deliver scheduled report. reportRequestId={}",
                    claimedRequest.getReportRequestId(),
                    exception
            );
        }
        if (cancellation != null) {
            handlePiggybackCancellation(cancellation, session);
        }
    }

    private void handlePiggybackCancellation(
            OadrCancelReportType cancellation,
            OpenAdrSessionSnapshot session
    ) {
        ReportRequestStore.CancellationBatch batch = beginCancellation(cancellation);
        if (!batch.accepted()) {
            log.warn(
                    "Ignoring invalid piggyback report cancellation. requestId={}, "
                            + "invalidReportRequestIds={}",
                    cancellation.getRequestID(),
                    batch.invalidReportRequestIds()
            );
            return;
        }

        deliverFinalReportsIfRequested(cancellation, batch, session);
    }

    private ReportRequestStore.CancellationBatch beginCancellation(
            OadrCancelReportType cancellation
    ) {
        return requestStore.beginCancellation(
                cancellation.getReportRequestID(),
                cancellation.isReportToFollow()
        );
    }

    private void deliverFinalReportsIfRequested(
            OadrCancelReportType cancellation,
            ReportRequestStore.CancellationBatch batch,
            OpenAdrSessionSnapshot session
    ) {
        if (batch.accepted() && cancellation.isReportToFollow()) {
            batch.requests().forEach(request -> deliverFinal(request, session));
        }
    }

    private void deliverFinal(
            ReportRequest request,
            OpenAdrSessionSnapshot session
    ) {
        var claimed = requestStore.claimFinal(
                request.getReportRequestId(),
                clock.instant()
        );
        if (claimed.isEmpty()) {
            return;
        }
        ReportRequestStore.DeliveryClaim claim = claimed.orElseThrow();
        ReportRequest claimedRequest = claim.request();
        try {
            if (isMetadata(claimedRequest)) {
                sendMetadata(claimedRequest, session);
                requestStore.completeFinalCancellation(claim);
                return;
            }

            ReportSchedule schedule = ReportSchedule.restore(claimedRequest);
            Instant now = clock.instant();
            Instant effectiveEnd = schedule.endExclusive() == null
                    || now.isBefore(schedule.endExclusive())
                    ? now
                    : schedule.endExclusive();
            if (claimedRequest.getLastReportedAt() != null
                    && !claimedRequest.getLastReportedAt().isBefore(effectiveEnd)) {
                sendTelemetry(
                        claimedRequest,
                        telemetryReportFactory.oneShot(claimedRequest),
                        session
                );
                requestStore.completeFinalCancellation(claim);
                return;
            }
            TimeRange window = schedule.deliveryWindow(
                    effectiveEnd,
                    claimedRequest.getLastReportedAt()
            );
            sendTelemetry(
                    claimedRequest,
                    telemetryReportFactory.periodic(
                            claimedRequest,
                            schedule,
                            window,
                            now
                    ),
                    session
            );
            requestStore.completeFinalCancellation(claim);
        } catch (RuntimeException exception) {
            requestStore.releaseDelivery(claim);
            log.error(
                    "Final report delivery failed and remains pending. reportRequestId={}",
                    claimedRequest.getReportRequestId(),
                    exception
            );
        }
    }

    private void sendMetadata(
            ReportRequest request,
            OpenAdrSessionSnapshot session
    ) {
        OadrRegisterReportType payload = reportService.buildMetadataRegisterReport(
                request.getReportRequestId(),
                session
        );
        transportService.send(OpenAdrOperations.REGISTER_REPORT, payload, session);
        requestStore.cancelNonMetadataRequests();
    }

    private OadrCancelReportType sendTelemetry(
            ReportRequest request,
            OadrReportType report,
            OpenAdrSessionSnapshot session
    ) {
        OadrUpdateReportType payload = Oadr20bEiReportBuilders
                .newOadr20bUpdateReportBuilder(RequestUtils.newRequestId(), session.venId())
                .addReport(report)
                .build();
        OadrUpdatedReportType response = transportService.send(
                OpenAdrOperations.UPDATE_REPORT,
                payload,
                session
        );
        log.info(
                "oadrUpdateReport acknowledged. reportRequestId={}, responseCode={}",
                request.getReportRequestId(),
                response.getEiResponse().getResponseCode()
        );
        return response.getOadrCancelReport();
    }

    private boolean isMetadata(ReportRequest request) {
        return ReportService.REPORT_SPECIFIER_ID_METADATA.equalsIgnoreCase(
                request.getReportSpecifierId()
        );
    }

}
