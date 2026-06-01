package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenReport;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.builders.eireport.PowerRealUnitType;
import com.qcharge.openadr.model.oadr20b.ei.ReadingTypeEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportDescriptionType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.siscale.SiScaleCodeType;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    public static final String REPORT_SPECIFIER_ID_TELEMETRY_USAGE = "qcharge_telemetry_usage";
    public static final String REPORT_SPECIFIER_ID_TELEMETRY_STATUS = "qcharge_telemetry_status";

    public static final String RID_POWER = "qcharge_power";
    public static final String RID_ENERGY = "qcharge_energy";
    public static final String RID_RESOURCE_STATUS = "qcharge_resource_status";

    private static final String RESPONSE_OK = "200";

    private final OpenAdrProperties properties;
    private final VenReportRepository reportRepository;
    private final VtnTransportService transportService;

    @Transactional
    public OadrRegisteredReportType registerReportingCapabilities() {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        log.info("Registering reporting capabilities. venId={}", venId);

        reportRepository.deleteAll();

        OadrRegisterReportType registerReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder(requestId, venId)
                .addOadrReport(buildTelemetryUsageMetadataReport())
                .addOadrReport(buildTelemetryStatusMetadataReport())
                .build();

        saveCapability(REPORT_SPECIFIER_ID_TELEMETRY_USAGE, ReportNameEnumeratedType.TELEMETRY_USAGE.value());
        saveCapability(REPORT_SPECIFIER_ID_TELEMETRY_STATUS, ReportNameEnumeratedType.TELEMETRY_STATUS.value());

        Object response = transportService.send(Oadr20bUrlPath.EI_REPORT_SERVICE, registerReport);

        if (!(response instanceof OadrRegisteredReportType registeredReport)) {
            throw new IllegalStateException(
                    "Unexpected response to oadrRegisterReport: "
                            + (response == null ? "null" : response.getClass().getName())
            );
        }

        validateRegisteredReportResponse(registeredReport);

        log.info("Reporting capabilities registered successfully");

        return registeredReport;
    }

    public OadrRegisterReportType buildMetadataRegisterReport(String reportRequestId) {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        OadrRegisterReportType registerReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder(requestId, venId)
                .addOadrReport(buildTelemetryUsageMetadataReport())
                .addOadrReport(buildTelemetryStatusMetadataReport())
                .build();

        if (reportRequestId != null && !reportRequestId.isBlank()) {
            registerReport.setReportRequestID(reportRequestId);
        }

        return registerReport;
    }

    public OadrReportType buildTelemetryUsageUpdateReport(
            String reportSpecifierId,
            String reportRequestId,
            int intervalSeconds
    ) {
        long now = System.currentTimeMillis();
        String duration = toXmlDuration(intervalSeconds);

        var powerInterval = Oadr20bFactory.createReportIntervalType(
                "power-" + UUID.randomUUID(),
                now,
                duration,
                RID_POWER,
                null,
                null,
                currentPowerKw()
        );

        var energyInterval = Oadr20bFactory.createReportIntervalType(
                "energy-" + UUID.randomUUID(),
                now,
                duration,
                RID_ENERGY,
                null,
                null,
                currentEnergyKwh()
        );

        return Oadr20bEiReportBuilders
                .newOadr20bUpdateReportOadrReportBuilder(
                        UUID.randomUUID().toString(),
                        reportSpecifierId,
                        reportRequestId,
                        ReportNameEnumeratedType.TELEMETRY_USAGE,
                        now,
                        now,
                        duration
                )
                .addInterval(powerInterval)
                .addInterval(energyInterval)
                .build();
    }

    public OadrReportType buildTelemetryStatusUpdateReport(
            String reportSpecifierId,
            String reportRequestId,
            int intervalSeconds
    ) {
        long now = System.currentTimeMillis();
        String duration = toXmlDuration(intervalSeconds);

        var capacity = Oadr20bFactory.createOadrLoadControlStateTypeType(
                1.0f,
                1.0f,
                0.0f,
                1.0f
        );

        var loadControlState = Oadr20bFactory.createOadrLoadControlStateType(
                capacity,
                null,
                null,
                null
        );

        var resourceStatus = Oadr20bFactory.createOadrPayloadResourceStatusType(
                loadControlState,
                false,
                true
        );

        var statusInterval = Oadr20bFactory.createReportIntervalType(
                "status-" + UUID.randomUUID(),
                now,
                duration,
                RID_RESOURCE_STATUS,
                null,
                null,
                resourceStatus
        );

        return Oadr20bEiReportBuilders
                .newOadr20bUpdateReportOadrReportBuilder(
                        UUID.randomUUID().toString(),
                        reportSpecifierId,
                        reportRequestId,
                        ReportNameEnumeratedType.TELEMETRY_STATUS,
                        now,
                        now,
                        duration
                )
                .addInterval(statusInterval)
                .build();
    }

    private OadrReportType buildTelemetryUsageMetadataReport() {
        OadrReportDescriptionType powerDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_POWER,
                        ReportEnumeratedType.READING,
                        ReadingTypeEnumeratedType.DIRECT_READ
                )
                .withPowerRealBase(
                        PowerRealUnitType.WATT,
                        SiScaleCodeType.KILO,
                        BigDecimal.valueOf(50.0),
                        BigDecimal.valueOf(230.0),
                        true
                )
                .withOadrSamplingRate("PT10S", "PT60S", false)
                .build();

        OadrReportDescriptionType energyDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_ENERGY,
                        ReportEnumeratedType.READING,
                        ReadingTypeEnumeratedType.DIRECT_READ
                )
                .withEnergyRealBase(SiScaleCodeType.KILO)
                .withOadrSamplingRate("PT60S", "PT300S", false)
                .build();

        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        REPORT_SPECIFIER_ID_TELEMETRY_USAGE,
                        ReportNameEnumeratedType.METADATA_TELEMETRY_USAGE,
                        System.currentTimeMillis()
                )
                .addReportDescription(powerDescriptor)
                .addReportDescription(energyDescriptor)
                .build();
    }

    private OadrReportType buildTelemetryStatusMetadataReport() {
        OadrReportDescriptionType statusDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_RESOURCE_STATUS,
                        ReportEnumeratedType.READING,
                        ReadingTypeEnumeratedType.DIRECT_READ
                )
                .withOadrSamplingRate("PT10S", "PT60S", false)
                .build();

        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        REPORT_SPECIFIER_ID_TELEMETRY_STATUS,
                        ReportNameEnumeratedType.METADATA_TELEMETRY_STATUS,
                        System.currentTimeMillis()
                )
                .addReportDescription(statusDescriptor)
                .build();
    }

    private void validateRegisteredReportResponse(OadrRegisteredReportType response) {
        String responseCode = response.getEiResponse().getResponseCode();

        if (!RESPONSE_OK.equals(responseCode)) {
            throw new IllegalStateException(
                    "oadrRegisterReport failed. code=%s, description=%s"
                            .formatted(responseCode, response.getEiResponse().getResponseDescription())
            );
        }
    }

    private void saveCapability(String reportSpecifierId, String reportName) {
        VenReport report = new VenReport();
        report.setReportSpecId(reportSpecifierId);
        report.setReportName(reportName);
        report.setStatus(VenReport.ReportStatus.REGISTERED);
        report.setGranularitySeconds(properties.getReport().getTelemetryIntervalSeconds());
        report.setCreatedAt(nowUtc());
        report.setUpdatedAt(nowUtc());

        reportRepository.save(report);
    }

    private String toXmlDuration(int seconds) {
        return "PT" + Math.max(1, seconds) + "S";
    }

    private float currentPowerKw() {
        // TODO: replace with real OCPP telemetry.
        return 0.0f;
    }

    private float currentEnergyKwh() {
        // TODO: replace with real OCPP telemetry.
        return 0.0f;
    }

    private Instant nowUtc() {
        return Instant.now();
    }
}