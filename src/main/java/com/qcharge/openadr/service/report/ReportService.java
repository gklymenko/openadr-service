package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
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
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
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
    private final ObjectProvider<RegistrationService> registrationServiceProvider;

    @Transactional
    public OadrRegisteredReportType registerReportingCapabilities(String venId) {
        String requestId = UUID.randomUUID().toString();

        log.info("Registering reporting capabilities. venId={}", venId);

        reportRepository.deleteAll();
        log.info("Remove all reports, venId={}", venId);

        OadrRegisterReportType registerReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder(requestId, venId)
                .addOadrReport(buildTelemetryUsageMetadataReport())
                .addOadrReport(buildTelemetryStatusMetadataReport())
                .build();

        // TH conformance rule: top-level reportRequestID MUST be empty
        // for metadata-only oadrRegisterReport (only present per-report, not at root)
        registerReport.setReportRequestID(null);

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
        String venId = currentVenId();
        String requestId = UUID.randomUUID().toString();

        OadrRegisterReportType registerReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder(requestId, venId)
                .addOadrReport(buildTelemetryUsageMetadataReport())
                .addOadrReport(buildTelemetryStatusMetadataReport())
                .build();

        // Clear factory default before conditional setting
        registerReport.setReportRequestID(reportRequestId != null && !reportRequestId.isBlank()
                ? reportRequestId
                : null);

        return registerReport;
    }

    public OadrReportType buildTelemetryUsageUpdateReport(
            String reportSpecifierId, String reportRequestId, int intervalSeconds, Set<String> requestedRids
    ) {
        long now = System.currentTimeMillis();
        String duration = toXmlDuration(intervalSeconds);

        var builder = Oadr20bEiReportBuilders
                .newOadr20bUpdateReportOadrReportBuilder(
                        UUID.randomUUID().toString(),
                        reportSpecifierId,
                        reportRequestId,
                        ReportNameEnumeratedType.TELEMETRY_USAGE,
                        now,
                        now,
                        duration
                );

        if (requestedRids.contains(RID_POWER)) {
            builder.addInterval(Oadr20bFactory.createReportIntervalType(
                    "power-" + UUID.randomUUID(),
                    now,
                    duration,
                    RID_POWER,
                    null,
                    null,
                    currentPowerKw()
            ));
        }

        if (requestedRids.contains(RID_ENERGY)) {
            builder.addInterval(Oadr20bFactory.createReportIntervalType(
                    "energy-" + UUID.randomUUID(),
                    now,
                    duration,
                    RID_ENERGY,
                    null,
                    null,
                    currentEnergyKwh()
            ));
        }

        return builder.build();
    }

    public OadrReportType buildTelemetryStatusUpdateReport(
            String reportSpecifierId, String reportRequestId, int intervalSeconds, Set<String> requestedRids
    ) {
        long now = System.currentTimeMillis();
        String duration = toXmlDuration(intervalSeconds);

        var builder = Oadr20bEiReportBuilders
                .newOadr20bUpdateReportOadrReportBuilder(
                        UUID.randomUUID().toString(),
                        reportSpecifierId,
                        reportRequestId,
                        ReportNameEnumeratedType.TELEMETRY_STATUS,
                        now,
                        now,
                        duration
                );

        if (!requestedRids.contains(RID_RESOURCE_STATUS)) {
            return builder.build();
        }

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

        builder.addInterval(Oadr20bFactory.createReportIntervalType(
                "status-" + UUID.randomUUID(),
                now,
                duration,
                RID_RESOURCE_STATUS,
                null,
                null,
                resourceStatus
        ));

        return builder.build();
    }

    public Set<String> supportedRidsFor(String reportSpecifierId) {
        if (REPORT_SPECIFIER_ID_TELEMETRY_USAGE.equals(reportSpecifierId)) {
            return Set.of(RID_POWER, RID_ENERGY);
        }

        if (REPORT_SPECIFIER_ID_TELEMETRY_STATUS.equals(reportSpecifierId)) {
            return Set.of(RID_RESOURCE_STATUS);
        }

        return Set.of();
    }

    private OadrReportType buildTelemetryUsageMetadataReport() {
        OadrReportDescriptionType powerDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_POWER,
                        ReportEnumeratedType.USAGE,
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
                        ReportEnumeratedType.USAGE,
                        ReadingTypeEnumeratedType.DIRECT_READ
                )
                .withEnergyRealBase(SiScaleCodeType.KILO)
                .withOadrSamplingRate("PT60S", "PT60S", false)
                .build();

        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        REPORT_SPECIFIER_ID_TELEMETRY_USAGE,
                        ReportNameEnumeratedType.METADATA_TELEMETRY_USAGE,
                        System.currentTimeMillis()
                )
                .withDuration(toXmlDuration(properties.getReport().getTelemetryIntervalSeconds()))
                .addReportDescription(powerDescriptor)
                .addReportDescription(energyDescriptor)
                .build();
    }

    private OadrReportType buildTelemetryStatusMetadataReport() {
        OadrReportDescriptionType statusDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_RESOURCE_STATUS,
                        ReportEnumeratedType.X_RESOURCE_STATUS,
                        ReadingTypeEnumeratedType.X_NOT_APPLICABLE
                )
                .withOadrSamplingRate("PT10S", "PT60S", false)
                .build();

        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        REPORT_SPECIFIER_ID_TELEMETRY_STATUS,
                        ReportNameEnumeratedType.METADATA_TELEMETRY_STATUS,
                        System.currentTimeMillis()
                )
                .withDuration(toXmlDuration(properties.getReport().getTelemetryIntervalSeconds()))
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
        VenReport report = reportRepository.findByReportSpecId(reportSpecifierId)
                .orElseGet(VenReport::new);

        report.setReportSpecId(reportSpecifierId);
        report.setReportName(reportName);
        report.setStatus(VenReport.ReportStatus.REGISTERED);
        report.setGranularitySeconds(properties.getReport().getTelemetryIntervalSeconds());

        if (report.getCreatedAt() == null) {
            report.setCreatedAt(nowUtc());
        }
        report.setUpdatedAt(nowUtc());

        reportRepository.save(report);
    }

    private String currentVenId() {
        return registrationServiceProvider.getObject().currentVenId();
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