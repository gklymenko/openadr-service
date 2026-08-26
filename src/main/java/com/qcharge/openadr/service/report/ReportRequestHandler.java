package com.qcharge.openadr.service.report;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportRequestHandler {

    private final ReportRequestStore requestStore;
    private final ReportRequestValidator requestValidator;
    private final ReportDeliveryCoordinator deliveryCoordinator;
    private final VtnTransportService transportService;
    private final Clock clock;

    public void handleRegisteredReport(
            OadrRegisteredReportType registeredReport,
            OpenAdrSessionSnapshot session
    ) {
        if (registeredReport.getOadrReportRequest().isEmpty()) {
            log.info("oadrRegisteredReport accepted without immediate report requests");
            return;
        }

        log.info(
                "oadrRegisteredReport contains {} report request(s)",
                registeredReport.getOadrReportRequest().size()
        );

        processReportRequests(
                registeredReport.getEiResponse().getRequestID(),
                registeredReport.getOadrReportRequest(),
                session
        );
    }

    public void handle(
            OadrCreateReportType createReport,
            OpenAdrSessionSnapshot session
    ) {
        log.info(
                "Received oadrCreateReport. requestId={}, requests={}",
                createReport.getRequestID(),
                createReport.getOadrReportRequest().size()
        );

        processReportRequests(
                createReport.getRequestID(),
                createReport.getOadrReportRequest(),
                session
        );
    }

    public void handleRegisterReport(
            OadrRegisterReportType registerReport,
            OpenAdrSessionSnapshot session
    ) {
        log.info(
                "Received oadrRegisterReport from VTN. requestId={}, reports={}",
                registerReport.getRequestID(),
                registerReport.getOadrReport().size()
        );

        OadrRegisteredReportType response = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder(
                        registerReport.getRequestID(),
                        OpenADRResponseCode.OK,
                        session.venId()
                )
                .build();

        transportService.send(
                OpenAdrOperations.REGISTERED_REPORT_RESPONSE,
                response,
                session
        );
    }

    public void handleCancelReport(
            OadrCancelReportType cancelReport,
            OpenAdrSessionSnapshot session
    ) {
        log.info(
                "Received oadrCancelReport. requestId={}, reportRequestIds={}",
                cancelReport.getRequestID(),
                cancelReport.getReportRequestID()
        );

        deliveryCoordinator.handleCancellation(cancelReport, session);
    }

    public void handleUpdateReport(
            OadrUpdateReportType updateReport,
            OpenAdrSessionSnapshot session
    ) {
        log.info(
                "Received oadrUpdateReport from VTN. requestId={}, reports={}",
                updateReport.getRequestID(),
                updateReport.getOadrReport().size()
        );

        OadrUpdatedReportType response = Oadr20bEiReportBuilders
                .newOadr20bUpdatedReportBuilder(
                        updateReport.getRequestID(),
                        OpenADRResponseCode.OK,
                        session.venId()
                )
                .build();

        transportService.send(
                OpenAdrOperations.UPDATED_REPORT_RESPONSE,
                response,
                session
        );
    }

    private void processReportRequests(
            String requestId,
            List<OadrReportRequestType> requests,
            OpenAdrSessionSnapshot session
    ) {
        List<ValidatedReportRequest> validatedRequests = requestValidator.validateAll(requests, requestId);
        Map<String, ReportRequest> persistedById = new HashMap<>();
        requestStore.activateAll(validatedRequests, clock.instant())
                .forEach(report -> persistedById.put(report.getReportRequestId(), report));

        List<ReportRequest> immediateReports = new ArrayList<>();
        List<String> pendingRequestIds = new ArrayList<>();

        for (ValidatedReportRequest request : validatedRequests) {
            ReportRequestResult result = processReportRequest(
                    request,
                    persistedById.get(request.reportRequestId())
            );

            if (result.pending()) {
                pendingRequestIds.add(request.reportRequestId());
            }

            if (result.immediateReport() != null) {
                immediateReports.add(result.immediateReport());
            }
        }

        sendCreatedReport(
                requestId,
                pendingRequestIds,
                session
        );

        immediateReports.forEach(report -> deliveryCoordinator.deliverOneShot(report, session));
    }

    private ReportRequestResult processReportRequest(
            ValidatedReportRequest request,
            ReportRequest persistedRequest
    ) {
        if (request.reportBackDuration().isZero()) {
            return ReportRequestResult.immediate(persistedRequest);
        }

        return ReportRequestResult.accepted();
    }

    private void sendCreatedReport(
            String requestId,
            List<String> pendingRequestIds,
            OpenAdrSessionSnapshot session
    ) {
        var builder = Oadr20bEiReportBuilders
                .newOadr20bCreatedReportBuilder(
                        requestId,
                        OpenADRResponseCode.OK,
                        session.venId()
                );

        pendingRequestIds.forEach(builder::addPendingReportRequestId);

        OadrCreatedReportType createdReport = builder.build();

        transportService.send(
                OpenAdrOperations.CREATED_REPORT_RESPONSE,
                createdReport,
                session
        );

        log.info(
                "Sent oadrCreatedReport. requestId={}, pendingRequests={}, responseCode={}",
                requestId,
                pendingRequestIds.size(),
                OpenADRResponseCode.OK
        );
    }

    private record ReportRequestResult(
            boolean pending, ReportRequest immediateReport
    ) {
        static ReportRequestResult accepted() {
            return new ReportRequestResult(true, null);
        }

        static ReportRequestResult immediate(ReportRequest report) {
            return new ReportRequestResult(false, report);
        }

    }
}
