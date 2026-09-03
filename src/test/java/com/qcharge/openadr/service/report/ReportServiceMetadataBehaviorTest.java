package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportServiceMetadataBehaviorTest {

    private ReportCapabilityRegistry capabilityRegistry;
    private ReportRequestStore requestStore;
    private VtnTransportService transportService;
    private OpenAdrResourceRepository resourceRepository;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getVen().setKey("primary");
        capabilityRegistry = mock(ReportCapabilityRegistry.class);
        requestStore = mock(ReportRequestStore.class);
        transportService = mock(VtnTransportService.class);
        resourceRepository = mock(OpenAdrResourceRepository.class);
        when(resourceRepository.findAllByVenKeyAndEnabledTrueOrderByResourceIdAsc("primary"))
                .thenReturn(List.of(
                        resource(101, "RESOURCE-1"),
                        resource(202, "RESOURCE-2")
                ));
        reportService = new ReportService(
                properties,
                capabilityRegistry,
                requestStore,
                transportService,
                resourceRepository,
                Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void r1_3170_successfulFullMetadataRegistrationImplicitlyCancelsTelemetryReports() {
        OadrRegisteredReportType response = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder("REGISTER", 200, "VEN-1")
                .build();
        doReturn(response).when(transportService).send(
                eq(OpenAdrOperations.REGISTER_REPORT),
                any(),
                eq(session())
        );

        reportService.registerReportingCapabilities(session());

        InOrder order = inOrder(transportService, requestStore, capabilityRegistry);
        order.verify(transportService).send(
                eq(OpenAdrOperations.REGISTER_REPORT),
                any(),
                eq(session())
        );
        order.verify(requestStore).cancelNonMetadataRequests();
        order.verify(capabilityRegistry).replaceAll(any());
    }

    @Test
    void metadataDescriptionsIdentifyEnabledResourcesForActiveVenKey() {
        var metadata = reportService.buildMetadataRegisterReport(
                "METADATA-REQUEST",
                session()
        );

        List<String> resourceIds = metadata.getOadrReport().stream()
                .flatMap(report -> report.getOadrReportDescription().stream())
                .flatMap(description -> description.getReportDataSource()
                        .getResourceID().stream())
                .toList();

        assertThat(resourceIds)
                .hasSize(6)
                .containsOnly("RESOURCE-1", "RESOURCE-2");

        assertThat(metadata.getOadrReport())
                .extracting(report -> report.getReportSpecifierID(), report -> report.getReportName())
                .containsExactly(
                        tuple("qcharge-usage-101", "METADATA_TELEMETRY_USAGE"),
                        tuple("qcharge-status-101", "METADATA_TELEMETRY_STATUS"),
                        tuple("qcharge-usage-202", "METADATA_TELEMETRY_USAGE"),
                        tuple("qcharge-status-202", "METADATA_TELEMETRY_STATUS")
                );

        Map<String, String> resourceBySpecifier = metadata.getOadrReport().stream()
                .collect(Collectors.toMap(
                        report -> report.getReportSpecifierID(),
                        report -> report.getOadrReportDescription().getFirst()
                                .getReportDataSource().getResourceID().getFirst(),
                        (left, right) -> left
                ));
        assertThat(resourceBySpecifier).containsExactlyInAnyOrderEntriesOf(Map.of(
                "qcharge-usage-101", "RESOURCE-1",
                "qcharge-status-101", "RESOURCE-1",
                "qcharge-usage-202", "RESOURCE-2",
                "qcharge-status-202", "RESOURCE-2"
        ));
    }

    @Test
    void metadataRegistrationWithoutEnabledResourcesAdvertisesNoCapabilities() {
        when(resourceRepository.findAllByVenKeyAndEnabledTrueOrderByResourceIdAsc("primary"))
                .thenReturn(List.of());

        var metadata = reportService.buildMetadataRegisterReport(
                "METADATA-REQUEST",
                session()
        );

        assertThat(metadata.getOadrReport()).isEmpty();
    }

    private OpenAdrResource resource(Integer chargePointPk, String resourceId) {
        OpenAdrResource resource = new OpenAdrResource();
        resource.setVenKey("primary");
        resource.setChargePointPk(chargePointPk);
        resource.setResourceId(resourceId);
        resource.setEnabled(true);
        return resource;
    }

    private OpenAdrSessionSnapshot session() {
        return new OpenAdrSessionSnapshot(
                1L,
                1L,
                "VEN-1",
                "VTN-1",
                "REGISTRATION-1",
                Duration.ofSeconds(10)
        );
    }
}
