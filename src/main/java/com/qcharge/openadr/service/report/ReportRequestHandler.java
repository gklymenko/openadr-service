package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenReport;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportRequestHandler {

    private final OpenAdrProperties properties;
    private final VenReportRepository reportRepository;
    private final VtnTransportService transportService;

    public void handle(OadrCreateReportType createReport) {
        String venId = properties.getVen().getId();
        log.info("Received oadrCreateReport with {} requests", createReport.getOadrReportRequest().size());

        for (OadrReportRequestType req : createReport.getOadrReportRequest()) {
            activateReport(req);
        }

        sendCreatedReport(createReport, venId);
        sendUpdateReport(createReport, venId);
    }

    private void activateReport(OadrReportRequestType req) {
        String reportRequestId = req.getReportRequestID();
        String reportSpecId = req.getReportSpecifier().getReportSpecifierID();
        int granularitySeconds = parseDurationSeconds(
                req.getReportSpecifier().getGranularity().getDuration());

        reportRepository.findByReportSpecId(reportSpecId).ifPresentOrElse(report -> {
            report.setReportRequestId(reportRequestId);
            report.setGranularitySeconds(granularitySeconds);
            report.setStatus(VenReport.ReportStatus.ACTIVE);
            report.setUpdatedAt(LocalDateTime.now());
            reportRepository.save(report);
            log.info("Activated report: reportSpecId={}, granularity={}s", reportSpecId, granularitySeconds);
        }, () -> log.warn("No registered report found for reportSpecId: {}", reportSpecId));
    }

    private void sendCreatedReport(OadrCreateReportType createReport, String venId) {
        var builder = Oadr20bEiReportBuilders
                .newOadr20bCreatedReportBuilder(UUID.randomUUID().toString(), 200, venId);

        createReport.getOadrReportRequest()
                .forEach(reportReqType -> builder.addPendingReportRequestId(reportReqType.getReportRequestID()));

        transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, builder.build());
        log.info("Sent oadrCreatedReport for {} requests", createReport.getOadrReportRequest().size());
    }

    private void sendUpdateReport(OadrCreateReportType createReport, String venId) {
        var updateBuilder = Oadr20bEiReportBuilders
                .newOadr20bUpdateReportBuilder(UUID.randomUUID().toString(), venId);

        for (OadrReportRequestType req : createReport.getOadrReportRequest()) {
            // TODO: replace with real charger telemetry from OCPP
            OadrReportType report = Oadr20bEiReportBuilders
                    .newOadr20bUpdateReportOadrReportBuilder(
                            UUID.randomUUID().toString(),
                            req.getReportSpecifier().getReportSpecifierID(),
                            req.getReportRequestID(),
                            ReportNameEnumeratedType.TELEMETRY_USAGE,
                            System.currentTimeMillis(),
                            System.currentTimeMillis(),
                            "PT60S")
                    .build();
            updateBuilder.addReport(report);
        }

        Object response = transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, updateBuilder.build());

        if (response instanceof OadrUpdatedReportType updated) {
            log.info("oadrUpdateReport accepted, code: {}", updated.getEiResponse().getResponseCode());
        } else {
            log.warn("Unexpected response to oadrUpdateReport: {}",
                    response != null ? response.getClass().getName() : "null");
        }
    }

    private int parseDurationSeconds(String isoDuration) {
        if (isoDuration == null) return 60;
        try {
            return (int) Duration.parse(isoDuration).getSeconds();
        } catch (Exception e) {
            log.warn("Could not parse duration '{}', defaulting to 60s", isoDuration);
            return 60;
        }
    }
}
