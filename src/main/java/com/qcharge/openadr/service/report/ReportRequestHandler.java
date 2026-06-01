package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.model.entity.VenReport;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.ei.SpecifierPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportRequestHandler {

    private static final String RESPONSE_OK = String.valueOf(ApplicationLayerErrorCodes.OK);
    private static final String METADATA_REPORT_SPECIFIER_ID = "METADATA";

    private final OpenAdrProperties properties;
    private final VenReportRepository reportRepository;
    private final VtnTransportService transportService;
    private final ReportService reportService;
    private final TaskScheduler openAdrTaskScheduler;

    private final Map<String, ScheduledFuture<?>> activeReportTasks = new ConcurrentHashMap<>();

    @Transactional
    public void handleRegisteredReport(OadrRegisteredReportType registeredReport) {
        String responseCode = registeredReport.getEiResponse().getResponseCode();

        if (!RESPONSE_OK.equals(responseCode)) {
            throw new IllegalStateException(
                    "oadrRegisteredReport failed. code=%s, description=%s"
                            .formatted(responseCode, registeredReport.getEiResponse().getResponseDescription())
            );
        }

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
                registeredReport.getOadrReportRequest()
        );
    }

    @Transactional
    public void handle(OadrCreateReportType createReport) {
        log.info(
                "Received oadrCreateReport. requestId={}, requests={}",
                createReport.getRequestID(),
                createReport.getOadrReportRequest().size()
        );

        processReportRequests(createReport.getRequestID(), createReport.getOadrReportRequest());
    }

    public void handleRegisterReport(OadrRegisterReportType registerReport) {
        log.info(
                "Received oadrRegisterReport from VTN. requestId={}, reports={}",
                registerReport.getRequestID(),
                registerReport.getOadrReport().size()
        );

        OadrRegisteredReportType response = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder(
                        registerReport.getRequestID(),
                        ApplicationLayerErrorCodes.OK,
                        properties.getVen().getId()
                )
                .build();

        transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, response);
    }

    @Transactional
    public void handleCancelReport(OadrCancelReportType cancelReport) {
        log.info(
                "Received oadrCancelReport. requestId={}, reportRequestIds={}",
                cancelReport.getRequestID(),
                cancelReport.getReportRequestID()
        );

        if (cancelReport.isReportToFollow()) {
            log.info("reportToFollow=true, sending final oadrUpdateReport before cancellation");

            if (cancelReport.getReportRequestID().isEmpty()) {
                reportRepository.findAll().stream()
                        .filter(report -> report.getStatus() == VenReport.ReportStatus.ACTIVE)
                        .forEach(this::sendUpdateReport);
            } else {
                cancelReport.getReportRequestID().forEach(reportRequestId ->
                        reportRepository.findByReportRequestId(reportRequestId)
                                .filter(report -> report.getStatus() == VenReport.ReportStatus.ACTIVE)
                                .ifPresent(this::sendUpdateReport)
                );
            }
        }

        boolean allCancelled = true;

        if (cancelReport.getReportRequestID().isEmpty()) {
            cancelAllReports();
        } else {
            for (String reportRequestId : cancelReport.getReportRequestID()) {
                boolean cancelled = cancelReportRequest(reportRequestId);
                allCancelled = allCancelled && cancelled;
            }
        }

        OadrCanceledReportType response = Oadr20bEiReportBuilders
                .newOadr20bCanceledReportBuilder(
                        cancelReport.getRequestID(),
                        allCancelled ? ApplicationLayerErrorCodes.OK : ApplicationLayerErrorCodes.REPORT_NOT_SUPPORTED,
                        properties.getVen().getId()
                )
                .build();

        transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, response);
    }

    public void handleUpdateReport(OadrUpdateReportType updateReport) {
        log.info(
                "Received oadrUpdateReport from VTN. requestId={}, reports={}",
                updateReport.getRequestID(),
                updateReport.getOadrReport().size()
        );

        OadrUpdatedReportType response = Oadr20bEiReportBuilders
                .newOadr20bUpdatedReportBuilder(
                        updateReport.getRequestID(),
                        ApplicationLayerErrorCodes.OK,
                        properties.getVen().getId()
                )
                .build();

        transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, response);
    }

    private void processReportRequests(String requestId, List<OadrReportRequestType> requests) {
        List<VenReport> immediateReports = new ArrayList<>();
        List<String> pendingRequestIds = new ArrayList<>();

        boolean allSupported = true;

        for (OadrReportRequestType request : requests) {
            ReportRequestResult result = processReportRequest(request);

            if (result.supported() && result.pending()) {
                pendingRequestIds.add(request.getReportRequestID());
            }

            if (!result.supported()) {
                allSupported = false;
            }

            if (result.immediateReport() != null) {
                immediateReports.add(result.immediateReport());
            }
        }

        sendCreatedReport(
                requestId, pendingRequestIds,
                allSupported ? ApplicationLayerErrorCodes.OK : ApplicationLayerErrorCodes.REPORT_NOT_SUPPORTED
        );

        immediateReports.forEach(this::sendUpdateReport);
    }

    private ReportRequestResult processReportRequest(OadrReportRequestType request) {
        String reportRequestId = request.getReportRequestID();
        String reportSpecifierId = request.getReportSpecifier().getReportSpecifierID();

        if (METADATA_REPORT_SPECIFIER_ID.equalsIgnoreCase(reportSpecifierId)) {
            sendMetadataReportResponse(reportRequestId);
            return ReportRequestResult.acceptedNotPending();
        }

        VenReport report = reportRepository
                .findByReportSpecId(reportSpecifierId)
                .orElse(null);

        if (report == null) {
            log.warn(
                    "Unsupported reportSpecifierId requested. reportSpecifierId={}, reportRequestId={}",
                    reportSpecifierId,
                    reportRequestId
            );
            return ReportRequestResult.unsupported();
        }

        Set<String> requestedRids = requestedRids(request);
        Set<String> supportedRids = reportService.supportedRidsFor(reportSpecifierId);

        if (requestedRids.isEmpty()) {
            log.warn(
                    "Report request has no requested rIDs. reportSpecifierId={}, reportRequestId={}",
                    reportSpecifierId,
                    reportRequestId
            );
            return ReportRequestResult.unsupported();
        }

        if (!supportedRids.containsAll(requestedRids)) {
            log.warn(
                    "Unsupported rID requested. reportSpecifierId={}, reportRequestId={}, requestedRids={}, supportedRids={}",
                    reportSpecifierId,
                    reportRequestId,
                    requestedRids,
                    supportedRids
            );
            return ReportRequestResult.unsupported();
        }

        activateReport(report, request, requestedRids);

        Duration reportBackDuration = parseDuration(
                request.getReportSpecifier().getReportBackDuration() != null
                        ? request.getReportSpecifier().getReportBackDuration().getDuration()
                        : null,
                Duration.ZERO
        );

        if (reportBackDuration.isZero()) {
            return ReportRequestResult.immediate(report);
        }

        scheduleRecurringReport(report, reportBackDuration);
        return ReportRequestResult.accepted();
    }

    private void sendMetadataReportResponse(String reportRequestId) {
        log.info("Sending METADATA oadrRegisterReport. reportRequestId={}", reportRequestId);

        OadrRegisterReportType metadataResponse = reportService.buildMetadataRegisterReport(reportRequestId);

        transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, metadataResponse);
    }

    private void sendCreatedReport(String requestId, List<String> pendingRequestIds, int responseCode) {
        var builder = Oadr20bEiReportBuilders
                .newOadr20bCreatedReportBuilder(
                        requestId,
                        responseCode,
                        properties.getVen().getId()
                );

        pendingRequestIds.forEach(builder::addPendingReportRequestId);

        OadrCreatedReportType createdReport = builder.build();

        transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, createdReport);

        log.info(
                "Sent oadrCreatedReport. requestId={}, pendingRequests={}, responseCode={}",
                requestId,
                pendingRequestIds.size(),
                responseCode
        );
    }

    private void activateReport(VenReport report, OadrReportRequestType request, Set<String> requestedRids) {
        report.setRequestedRids(String.join(",", requestedRids));

        Duration granularity = parseDuration(
                request.getReportSpecifier().getGranularity() != null
                        ? request.getReportSpecifier().getGranularity().getDuration()
                        : null,
                Duration.ofSeconds(properties.getReport().getTelemetryIntervalSeconds())
        );

        report.setReportRequestId(request.getReportRequestID());
        report.setGranularitySeconds((int) granularity.toSeconds());
        report.setStatus(VenReport.ReportStatus.ACTIVE);
        report.setUpdatedAt(nowUtc());

        reportRepository.save(report);

        log.info(
                "Activated report. reportSpecifierId={}, reportRequestId={}, granularity={}",
                report.getReportSpecId(),
                report.getReportRequestId(),
                granularity
        );
    }

    private void scheduleRecurringReport(VenReport report, Duration reportBackDuration) {
        cancelTask(report.getReportRequestId());

        ScheduledFuture<?> task = openAdrTaskScheduler.scheduleWithFixedDelay(
                () -> safeSendUpdateReport(report.getReportRequestId()),
                Instant.now().plus(reportBackDuration),
                reportBackDuration
        );

        activeReportTasks.put(report.getReportRequestId(), task);

        log.info(
                "Scheduled recurring report. reportSpecifierId={}, reportRequestId={}, reportBackDuration={}",
                report.getReportSpecId(),
                report.getReportRequestId(),
                reportBackDuration
        );
    }

    private void safeSendUpdateReport(String reportRequestId) {
        try {
            reportRepository.findByReportRequestId(reportRequestId)
                    .filter(report -> report.getStatus() == VenReport.ReportStatus.ACTIVE)
                    .ifPresent(this::sendUpdateReport);
        } catch (Exception e) {
            log.error("Failed to send scheduled oadrUpdateReport. reportRequestId={}", reportRequestId, e);
        }
    }

    private void sendUpdateReport(VenReport report) {
        OadrUpdateReportType updateReport = Oadr20bEiReportBuilders
                .newOadr20bUpdateReportBuilder(
                        java.util.UUID.randomUUID().toString(),
                        properties.getVen().getId()
                )
                .addReport(buildReportPayload(report))
                .build();

        Object response = transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, updateReport);

        if (response instanceof OadrUpdatedReportType updatedReport) {
            log.info(
                    "oadrUpdateReport acknowledged. reportRequestId={}, responseCode={}",
                    report.getReportRequestId(),
                    updatedReport.getEiResponse().getResponseCode()
            );

            if (updatedReport.getOadrCancelReport() != null) {
                handleCancelReport(updatedReport.getOadrCancelReport());
            }

            return;
        }

        log.warn(
                "Unexpected response to oadrUpdateReport. reportRequestId={}, responseType={}",
                report.getReportRequestId(),
                response == null ? "null" : response.getClass().getName()
        );
    }

    private OadrReportType buildReportPayload(VenReport report) {
        int intervalSeconds = report.getGranularitySeconds() != null
                ? report.getGranularitySeconds()
                : properties.getReport().getTelemetryIntervalSeconds();

        Set<String> requestedRids = parseRequestedRids(report.getRequestedRids());

        if (requestedRids.isEmpty()) {
            requestedRids = reportService.supportedRidsFor(report.getReportSpecId());
        }

        if (ReportService.REPORT_SPECIFIER_ID_TELEMETRY_STATUS.equals(report.getReportSpecId())) {
            return reportService.buildTelemetryStatusUpdateReport(
                    report.getReportSpecId(),
                    report.getReportRequestId(),
                    intervalSeconds,
                    requestedRids
            );
        }

        return reportService.buildTelemetryUsageUpdateReport(
                report.getReportSpecId(),
                report.getReportRequestId(),
                intervalSeconds,
                requestedRids
        );
    }

    private boolean cancelReportRequest(String reportRequestId) {
        cancelTask(reportRequestId);

        return reportRepository.findByReportRequestId(reportRequestId)
                .map(report -> {
                    report.setStatus(VenReport.ReportStatus.CANCELLED);
                    report.setUpdatedAt(nowUtc());
                    reportRepository.save(report);

                    log.info(
                            "Cancelled report. reportSpecifierId={}, reportRequestId={}",
                            report.getReportSpecId(),
                            reportRequestId
                    );

                    return true;
                })
                .orElseGet(() -> {
                    log.warn("Could not cancel unknown reportRequestId={}", reportRequestId);
                    return false;
                });
    }

    private void cancelAllReports() {
        activeReportTasks.keySet().forEach(this::cancelTask);

        reportRepository.findAll().stream()
                .filter(report -> report.getStatus() == VenReport.ReportStatus.ACTIVE)
                .forEach(report -> {
                    report.setStatus(VenReport.ReportStatus.CANCELLED);
                    report.setUpdatedAt(nowUtc());
                    reportRepository.save(report);
                });

        log.info("Cancelled all active reports");
    }

    private void cancelTask(String reportRequestId) {
        ScheduledFuture<?> task = activeReportTasks.remove(reportRequestId);

        if (task != null) {
            task.cancel(false);
        }
    }

    private Duration parseDuration(String rawValue, Duration fallback) {
        try {
            return OpenAdrTimeUtils.parseOpenAdrDuration(rawValue)
                    .orElse(fallback);
        } catch (RuntimeException e) {
            log.warn("Invalid OpenADR duration={}. Using fallback={}", rawValue, fallback);
            return fallback;
        }
    }

    private Instant nowUtc() {
        return Instant.now();
    }

    private record ReportRequestResult(
            boolean supported, boolean pending, VenReport immediateReport
    ) {
        static ReportRequestResult accepted() {
            return new ReportRequestResult(true, true, null);
        }

        static ReportRequestResult unsupported() {
            return new ReportRequestResult(false, false, null);
        }

        static ReportRequestResult immediate(VenReport report) {
            return new ReportRequestResult(true, false, report);
        }

        static ReportRequestResult acceptedNotPending() {
            return new ReportRequestResult(true, false, null);
        }
    }

    private Set<String> requestedRids(OadrReportRequestType request) {
        if (request == null
                || request.getReportSpecifier() == null
                || request.getReportSpecifier().getSpecifierPayload().isEmpty()) {
            return Set.of();
        }

        Set<String> rids = new LinkedHashSet<>();

        for (SpecifierPayloadType payload : request.getReportSpecifier().getSpecifierPayload()) {
            if (payload.getRID() != null && !payload.getRID().isBlank()) {
                rids.add(payload.getRID());
            }
        }

        return rids;
    }

    private Set<String> parseRequestedRids(String requestedRids) {
        if (requestedRids == null || requestedRids.isBlank()) {
            return Set.of();
        }

        return java.util.Arrays.stream(requestedRids.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}