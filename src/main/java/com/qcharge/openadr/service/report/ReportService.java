package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
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
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.utility.RequestUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    public static final String REPORT_SPECIFIER_ID_METADATA = "METADATA";
    public static final String REPORT_SPECIFIER_ID_TELEMETRY_USAGE = "qcharge_telemetry_usage";
    public static final String REPORT_SPECIFIER_ID_TELEMETRY_STATUS = "qcharge_telemetry_status";

    public static final String RID_POWER = "qcharge_power";
    public static final String RID_ENERGY = "qcharge_energy";
    public static final String RID_RESOURCE_STATUS = "qcharge_resource_status";

    private final OpenAdrProperties properties;
    private final ReportCapabilityRegistry capabilityRegistry;
    private final VtnTransportService transportService;
    private final Clock clock;

    public OadrRegisteredReportType registerReportingCapabilities(
            OpenAdrSessionSnapshot session
    ) {
        String venId = session.venId();
        String requestId = RequestUtils.newRequestId();

        log.info("Registering reporting capabilities. venId={}", venId);

        OadrRegisterReportType registerReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder(requestId, venId)
                .addOadrReport(buildTelemetryUsageMetadataReport())
                .addOadrReport(buildTelemetryStatusMetadataReport())
                .build();

        // TH conformance rule: top-level reportRequestID MUST be empty
        // for metadata-only oadrRegisterReport (only present per-report, not at root)
        registerReport.setReportRequestID(null);

        OadrRegisteredReportType registeredReport = transportService.send(
                OpenAdrOperations.REGISTER_REPORT,
                registerReport,
                session
        );
        capabilityRegistry.replaceAll(capabilityDefinitions());

        log.info("Reporting capabilities registered successfully");

        return registeredReport;
    }

    public OadrRegisterReportType buildMetadataRegisterReport(
            String reportRequestId,
            OpenAdrSessionSnapshot session
    ) {
        String venId = session.venId();
        String requestId = RequestUtils.newRequestId();

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
                .withOadrSamplingRate(minSamplingPeriod().toString(), maxSamplingPeriod().toString(), false)
                .build();

        OadrReportDescriptionType energyDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_ENERGY,
                        ReportEnumeratedType.USAGE,
                        ReadingTypeEnumeratedType.DIRECT_READ
                )
                .withEnergyRealBase(SiScaleCodeType.KILO)
                .withOadrSamplingRate(minSamplingPeriod().toString(), maxSamplingPeriod().toString(), false)
                .build();

        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        REPORT_SPECIFIER_ID_TELEMETRY_USAGE,
                        ReportNameEnumeratedType.METADATA_TELEMETRY_USAGE,
                        clock.instant().toEpochMilli()
                )
                .withDuration(availableDuration().toString())
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
                .withOadrSamplingRate(minSamplingPeriod().toString(), maxSamplingPeriod().toString(), false)
                .build();

        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        REPORT_SPECIFIER_ID_TELEMETRY_STATUS,
                        ReportNameEnumeratedType.METADATA_TELEMETRY_STATUS,
                        clock.instant().toEpochMilli()
                )
                .withDuration(availableDuration().toString())
                .addReportDescription(statusDescriptor)
                .build();
    }

    private List<ReportCapabilityRegistry.Definition> capabilityDefinitions() {
        return List.of(
                new ReportCapabilityRegistry.Definition(
                        REPORT_SPECIFIER_ID_TELEMETRY_USAGE,
                        ReportNameEnumeratedType.TELEMETRY_USAGE.value(),
                        Set.of(RID_POWER, RID_ENERGY),
                        minSamplingPeriod(),
                        maxSamplingPeriod(),
                        availableDuration()
                ),
                new ReportCapabilityRegistry.Definition(
                        REPORT_SPECIFIER_ID_TELEMETRY_STATUS,
                        ReportNameEnumeratedType.TELEMETRY_STATUS.value(),
                        Set.of(RID_RESOURCE_STATUS),
                        minSamplingPeriod(),
                        maxSamplingPeriod(),
                        availableDuration()
                )
        );
    }

    private Duration minSamplingPeriod() {
        return Duration.ofSeconds(properties.getReport().getTelemetryIntervalSeconds());
    }

    private Duration maxSamplingPeriod() {
        return Duration.ofSeconds(Math.max(
                60,
                properties.getReport().getTelemetryIntervalSeconds()
        ));
    }

    private Duration availableDuration() {
        return Duration.ofSeconds(properties.getReport().getTelemetryRetentionSeconds());
    }

}
