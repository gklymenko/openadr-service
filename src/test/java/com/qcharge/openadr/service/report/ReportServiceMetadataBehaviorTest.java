package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class ReportServiceMetadataBehaviorTest {

    private ReportCapabilityRegistry capabilityRegistry;
    private ReportRequestStore requestStore;
    private VtnTransportService transportService;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getReport().setResourceId("RESOURCE-1");
        capabilityRegistry = mock(ReportCapabilityRegistry.class);
        requestStore = mock(ReportRequestStore.class);
        transportService = mock(VtnTransportService.class);
        reportService = new ReportService(
                properties,
                capabilityRegistry,
                requestStore,
                transportService,
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
