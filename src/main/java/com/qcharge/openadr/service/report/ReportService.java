package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.builders.eireport.PowerRealUnitType;
import com.qcharge.openadr.model.oadr20b.ei.EiTargetType;
import com.qcharge.openadr.model.oadr20b.ei.ReadingTypeEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportDescriptionType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.siscale.SiScaleCodeType;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    public static final String REPORT_SPECIFIER_ID_METADATA = "METADATA";
    private static final String REPORT_SPECIFIER_ID_USAGE_PREFIX = "qcharge-usage-";
    private static final String REPORT_SPECIFIER_ID_STATUS_PREFIX = "qcharge-status-";

    public static final String RID_POWER = "qcharge_power";
    public static final String RID_ENERGY = "qcharge_energy";
    public static final String RID_RESOURCE_STATUS = "qcharge_resource_status";

    private final OpenAdrProperties properties;
    private final ReportCapabilityRegistry capabilityRegistry;
    private final ReportRequestStore requestStore;
    private final VtnTransportService transportService;
    private final OpenAdrResourceRepository resourceRepository;
    private final Clock clock;

    public OadrRegisteredReportType registerReportingCapabilities(OpenAdrSessionSnapshot session) {
        String venId = session.venId();
        String requestId = RequestUtils.newRequestId();
        List<OpenAdrResource> resources = enabledResources();

        log.info(
                "Registering reporting capabilities. venId={}, venKey={}, resources={}",
                venId, properties.getVen().getKey(),
                resources.stream().map(OpenAdrResource::getResourceId).toList()
        );

        OadrRegisterReportType registerReport = buildRegisterReport(requestId, venId, resources);

        // TH conformance rule: top-level reportRequestID MUST be empty
        // for metadata-only oadrRegisterReport (only present per-report, not at root)
        registerReport.setReportRequestID(null);

        OadrRegisteredReportType registeredReport = transportService.send(
                OpenAdrOperations.REGISTER_REPORT,
                registerReport,
                session
        );
        requestStore.cancelNonMetadataRequests();
        capabilityRegistry.replaceAll(capabilityDefinitions(resources));

        log.info("Reporting capabilities registered successfully");

        return registeredReport;
    }

    public OadrRegisterReportType buildMetadataRegisterReport(
            String reportRequestId, OpenAdrSessionSnapshot session
    ) {
        String venId = session.venId();
        String requestId = RequestUtils.newRequestId();
        OadrRegisterReportType registerReport = buildRegisterReport(
                requestId,
                venId,
                enabledResources()
        );

        // Clear factory default before conditional setting
        registerReport.setReportRequestID(reportRequestId != null && !reportRequestId.isBlank()
                ? reportRequestId
                : null);

        return registerReport;
    }

    private OadrRegisterReportType buildRegisterReport(
            String requestId, String venId, List<OpenAdrResource> resources
    ) {
        var builder = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder(requestId, venId);

        for (OpenAdrResource resource : resources) {
            OadrReportType usageMetadataReport = buildTelemetryUsageMetadataReport(resource);
            OadrReportType statusMetadataReport = buildTelemetryStatusMetadataReport(resource);

            builder.addOadrReport(usageMetadataReport);
            builder.addOadrReport(statusMetadataReport);
        }

        return builder.build();
    }

    private OadrReportType buildTelemetryUsageMetadataReport(OpenAdrResource resource) {
        OadrReportDescriptionType powerDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_POWER, ReportEnumeratedType.USAGE, ReadingTypeEnumeratedType.DIRECT_READ
                )
                .withPowerRealBase(
                        PowerRealUnitType.WATT,
                        SiScaleCodeType.KILO,
                        BigDecimal.valueOf(50.0),
                        BigDecimal.valueOf(230.0),
                        true
                )
                .withOadrSamplingRate(minSamplingPeriod().toString(), maxSamplingPeriod().toString(), false)
                .withDataSource(reportDataSource(resource.getResourceId()))
                .build();

        OadrReportDescriptionType energyDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_ENERGY,
                        ReportEnumeratedType.USAGE,
                        ReadingTypeEnumeratedType.DIRECT_READ
                )
                .withEnergyRealBase(SiScaleCodeType.KILO)
                .withOadrSamplingRate(minSamplingPeriod().toString(), maxSamplingPeriod().toString(), false)
                .withDataSource(reportDataSource(resource.getResourceId()))
                .build();

        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        usageReportSpecifierId(resource.getChargePointPk()),
                        ReportNameEnumeratedType.METADATA_TELEMETRY_USAGE,
                        clock.instant().toEpochMilli()
                )
                .withDuration(availableDuration().toString())
                .addReportDescription(powerDescriptor)
                .addReportDescription(energyDescriptor)
                .build();
    }

    private OadrReportType buildTelemetryStatusMetadataReport(OpenAdrResource resource) {
        OadrReportDescriptionType statusDescriptor = Oadr20bEiReportBuilders
                .newOadr20bOadrReportDescriptionBuilder(
                        RID_RESOURCE_STATUS,
                        ReportEnumeratedType.X_RESOURCE_STATUS,
                        ReadingTypeEnumeratedType.X_NOT_APPLICABLE
                )
                .withOadrSamplingRate(minSamplingPeriod().toString(), maxSamplingPeriod().toString(), false)
                .withDataSource(reportDataSource(resource.getResourceId()))
                .build();

        return Oadr20bEiReportBuilders
                .newOadr20bRegisterReportOadrReportBuilder(
                        statusReportSpecifierId(resource.getChargePointPk()),
                        ReportNameEnumeratedType.METADATA_TELEMETRY_STATUS,
                        clock.instant().toEpochMilli()
                )
                .withDuration(availableDuration().toString())
                .addReportDescription(statusDescriptor)
                .build();
    }

    private List<ReportCapabilityRegistry.Definition> capabilityDefinitions(
            List<OpenAdrResource> resources
    ) {
        List<ReportCapabilityRegistry.Definition> definitions = new ArrayList<>(resources.size() * 2);
        for (OpenAdrResource resource : resources) {
            definitions.add(new ReportCapabilityRegistry.Definition(
                    usageReportSpecifierId(resource.getChargePointPk()),
                    ReportNameEnumeratedType.TELEMETRY_USAGE.value(),
                    resource.getResourceId(),
                    Set.of(RID_POWER, RID_ENERGY),
                    minSamplingPeriod(),
                    maxSamplingPeriod(),
                    availableDuration()
            ));
            definitions.add(new ReportCapabilityRegistry.Definition(
                    statusReportSpecifierId(resource.getChargePointPk()),
                    ReportNameEnumeratedType.TELEMETRY_STATUS.value(),
                    resource.getResourceId(),
                    Set.of(RID_RESOURCE_STATUS),
                    minSamplingPeriod(),
                    maxSamplingPeriod(),
                    availableDuration()
            ));
        }
        return List.copyOf(definitions);
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

    private List<OpenAdrResource> enabledResources() {
        return resourceRepository.findAllByVenKeyAndEnabledTrueOrderByResourceIdAsc(
                properties.getVen().getKey()
        );
    }

    private EiTargetType reportDataSource(String resourceId) {
        return Oadr20bEiBuilders.newOadr20bEiTargetTypeBuilder()
                .addResourceId(resourceId)
                .build();
    }

    static String usageReportSpecifierId(Integer chargePointPk) {
        return REPORT_SPECIFIER_ID_USAGE_PREFIX + chargePointPk;
    }

    static String statusReportSpecifierId(Integer chargePointPk) {
        return REPORT_SPECIFIER_ID_STATUS_PREFIX + chargePointPk;
    }

}
