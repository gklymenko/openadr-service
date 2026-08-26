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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates due-report delivery without holding a database transaction during transport I/O. */
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

    private final Set<String> deliveriesInProgress = ConcurrentHashMap.newKeySet();

    public void deliverOneShot(
            ReportRequest request,
            OpenAdrSessionSnapshot session
    ) {
        if (!beginDelivery(request.getReportRequestId())) {
            return;
        }
        OadrCancelReportType cancellation = null;
        try {
            if (isMetadata(request)) {
                sendMetadata(request, session);
            } else {
                cancellation = sendTelemetry(
                        request,
                        telemetryReportFactory.oneShot(request),
                        session
                );
            }
            requestStore.complete(request.getReportRequestId());
        } finally {
            endDelivery(request.getReportRequestId());
        }
        if (cancellation != null) {
            handleCancellation(cancellation, session);
        }
    }

    public void deliverDueReports() {
        Instant now = clock.instant();
        OpenAdrSessionSnapshot session = lifecycleCoordinator.requireRegisteredSession();
        requestStore.findFinalReportsPending().forEach(request -> deliverFinal(request, session));
        requestStore.findDue(now).forEach(request -> deliverDue(request, session, now));
    }

    public void handleCancellation(
            OadrCancelReportType cancellation,
            OpenAdrSessionSnapshot session
    ) {
        ReportRequestStore.CancellationBatch batch = requestStore.beginCancellation(
                cancellation.getReportRequestID(),
                cancellation.isReportToFollow()
        );
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

        if (batch.accepted() && cancellation.isReportToFollow()) {
            batch.requests().forEach(request -> deliverFinal(request, session));
        }
    }

    private void deliverDue(
            ReportRequest request,
            OpenAdrSessionSnapshot session,
            Instant now
    ) {
        if (!beginDelivery(request.getReportRequestId())) {
            return;
        }
        OadrCancelReportType cancellation = null;
        try {
            ReportSchedule schedule = ReportSchedule.restore(request);
            Instant dueAt = request.getNextReportAt();
            if (dueAt == null || dueAt.isAfter(now)) {
                return;
            }

            Instant deliveredThrough;
            if (isMetadata(request)) {
                sendMetadata(request, session);
                deliveredThrough = dueAt;
            } else {
                TimeRange window = schedule.deliveryWindow(dueAt, request.getLastReportedAt());
                OadrReportType payload = telemetryReportFactory.periodic(
                        request,
                        schedule,
                        window,
                        now
                );
                cancellation = sendTelemetry(request, payload, session);
                deliveredThrough = window.endExclusive();
            }

            requestStore.recordDelivery(
                    request.getReportRequestId(),
                    deliveredThrough,
                    schedule.nextDeliveryAfter(deliveredThrough)
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to deliver scheduled report. reportRequestId={}",
                    request.getReportRequestId(),
                    exception
            );
        } finally {
            endDelivery(request.getReportRequestId());
        }
        if (cancellation != null) {
            handleCancellation(cancellation, session);
        }
    }

    private void deliverFinal(
            ReportRequest request,
            OpenAdrSessionSnapshot session
    ) {
        if (!beginDelivery(request.getReportRequestId())) {
            return;
        }
        try {
            if (isMetadata(request)) {
                sendMetadata(request, session);
                requestStore.completeFinalCancellation(request.getReportRequestId());
                return;
            }

            ReportSchedule schedule = ReportSchedule.restore(request);
            Instant now = clock.instant();
            Instant effectiveEnd = schedule.endExclusive() == null
                    || now.isBefore(schedule.endExclusive())
                    ? now
                    : schedule.endExclusive();
            if (request.getLastReportedAt() != null
                    && !request.getLastReportedAt().isBefore(effectiveEnd)) {
                sendTelemetry(
                        request,
                        telemetryReportFactory.oneShot(request),
                        session
                );
                requestStore.completeFinalCancellation(request.getReportRequestId());
                return;
            }
            TimeRange window = schedule.deliveryWindow(effectiveEnd, request.getLastReportedAt());
            sendTelemetry(
                    request,
                    telemetryReportFactory.periodic(request, schedule, window, now),
                    session
            );
            requestStore.completeFinalCancellation(request.getReportRequestId());
        } catch (RuntimeException exception) {
            log.error(
                    "Final report delivery failed and remains pending. reportRequestId={}",
                    request.getReportRequestId(),
                    exception
            );
        } finally {
            endDelivery(request.getReportRequestId());
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

    private boolean beginDelivery(String reportRequestId) {
        return deliveriesInProgress.add(reportRequestId);
    }

    private void endDelivery(String reportRequestId) {
        deliveriesInProgress.remove(reportRequestId);
    }
}
