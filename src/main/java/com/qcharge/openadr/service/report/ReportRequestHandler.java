package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.model.entity.VenReport;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            cancelReport.getReportRequestID().forEach(reportRequestId ->
                    reportRepository.findByReportRequestId(reportRequestId)
                            .ifPresent(this::sendUpdateReport)
            );
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
        List<String> acceptedRequestIds = new ArrayList<>();

        boolean allSupported = true;

        for (OadrReportRequestType request : requests) {
            ReportRequestResult result = processReportRequest(request);

            if (result.supported()) {
                acceptedRequestIds.add(request.getReportRequestID());
            } else {
                allSupported = false;
            }

            if (result.immediateReport() != null) {
                immediateReports.add(result.immediateReport());
            }
        }

        sendCreatedReport(
                requestId,
                acceptedRequestIds,
                allSupported ? ApplicationLayerErrorCodes.OK : ApplicationLayerErrorCodes.REPORT_NOT_SUPPORTED
        );

        immediateReports.forEach(this::sendUpdateReport);
    }

    private ReportRequestResult processReportRequest(OadrReportRequestType request) {
        String reportRequestId = request.getReportRequestID();
        String reportSpecifierId = request.getReportSpecifier().getReportSpecifierID();

        if (METADATA_REPORT_SPECIFIER_ID.equalsIgnoreCase(reportSpecifierId)) {
            sendMetadataReportResponse(reportRequestId);
            return ReportRequestResult.accepted();
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

        activateReport(report, request);

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

    private void sendCreatedReport(String requestId, List<String> acceptedRequestIds, int responseCode) {
        var builder = Oadr20bEiReportBuilders
                .newOadr20bCreatedReportBuilder(
                        requestId,
                        responseCode,
                        properties.getVen().getId()
                );

        acceptedRequestIds.forEach(builder::addPendingReportRequestId);

        OadrCreatedReportType createdReport = builder.build();

        transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, createdReport);

        log.info(
                "Sent oadrCreatedReport. requestId={}, acceptedRequests={}, responseCode={}",
                requestId,
                acceptedRequestIds.size(),
                responseCode
        );
    }

    private void activateReport(VenReport report, OadrReportRequestType request) {
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

        if (ReportService.REPORT_SPECIFIER_ID_TELEMETRY_STATUS.equals(report.getReportSpecId())) {
            return reportService.buildTelemetryStatusUpdateReport(
                    report.getReportSpecId(),
                    report.getReportRequestId(),
                    intervalSeconds
            );
        }

        return reportService.buildTelemetryUsageUpdateReport(
                report.getReportSpecId(),
                report.getReportRequestId(),
                intervalSeconds
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

        reportRepository.findAll().forEach(report -> {
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
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }

        if ("0".equals(rawValue)) {
            return Duration.ZERO;
        }

        try {
            return Duration.parse(rawValue);
        } catch (RuntimeException e) {
            log.warn("Could not parse OpenADR duration={}. Using fallback={}", rawValue, fallback);
            return fallback;
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record ReportRequestResult(boolean supported, VenReport immediateReport) {
        static ReportRequestResult accepted() {
            return new ReportRequestResult(true, null);
        }

        static ReportRequestResult unsupported() {
            return new ReportRequestResult(false, null);
        }

        static ReportRequestResult immediate(VenReport report) {
            return new ReportRequestResult(true, report);
        }
    }
}