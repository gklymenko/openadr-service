package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportRequestHandlerBehaviorTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    @Mock ReportRequestStore requestStore;
    @Mock ReportRequestValidator requestValidator;
    @Mock ReportDeliveryCoordinator deliveryCoordinator;
    @Mock VtnTransportService transportService;

    private ReportRequestHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReportRequestHandler(
                requestStore,
                requestValidator,
                deliveryCoordinator,
                transportService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void r1_3030_acceptsMultipleConcurrentPeriodicReports() {
        OadrReportRequestType firstPayload = new OadrReportRequestType();
        OadrReportRequestType secondPayload = new OadrReportRequestType();
        var createReport = Oadr20bEiReportBuilders
                .newOadr20bCreateReportBuilder("CREATE", "VEN-1")
                .addReportRequest(firstPayload)
                .addReportRequest(secondPayload)
                .build();
        List<ValidatedReportRequest> validated = List.of(
                validated("PERIODIC-1", Duration.ofMinutes(1)),
                validated("PERIODIC-2", Duration.ofMinutes(2))
        );
        List<ReportRequest> persisted = List.of(
                persisted("PERIODIC-1"),
                persisted("PERIODIC-2")
        );
        when(requestValidator.validateAll(
                createReport.getOadrReportRequest(),
                "CREATE"
        )).thenReturn(validated);
        when(requestStore.activateAll(validated, NOW)).thenReturn(persisted);
        when(requestStore.findAllPendingReportRequestIds())
                .thenReturn(List.of("PERIODIC-1", "PERIODIC-2"));
        ArgumentCaptor<OadrCreatedReportType> response =
                ArgumentCaptor.forClass(OadrCreatedReportType.class);

        handler.handle(createReport, session());

        verify(transportService).send(
                eq(OpenAdrOperations.CREATED_REPORT_RESPONSE),
                response.capture(),
                eq(session())
        );
        assertThat(response.getValue().getOadrPendingReports().getReportRequestID())
                .containsExactly("PERIODIC-1", "PERIODIC-2");
        verify(deliveryCoordinator, never()).deliverOneShot(any(), any());
    }

    @Test
    void r1_3180_acknowledgesBatchBeforeDeliveringBothOneShotReports() {
        OadrReportRequestType firstPayload = new OadrReportRequestType();
        OadrReportRequestType secondPayload = new OadrReportRequestType();
        var createReport = Oadr20bEiReportBuilders
                .newOadr20bCreateReportBuilder("CREATE", "VEN-1")
                .addReportRequest(firstPayload)
                .addReportRequest(secondPayload)
                .build();
        List<ValidatedReportRequest> validated = List.of(
                validated("ONE-SHOT-1", Duration.ZERO),
                validated("ONE-SHOT-2", Duration.ZERO)
        );
        ReportRequest first = persisted("ONE-SHOT-1");
        ReportRequest second = persisted("ONE-SHOT-2");
        when(requestValidator.validateAll(
                createReport.getOadrReportRequest(),
                "CREATE"
        )).thenReturn(validated);
        when(requestStore.activateAll(validated, NOW))
                .thenReturn(List.of(first, second));
        when(requestStore.findAllPendingReportRequestIds()).thenReturn(List.of());

        handler.handle(createReport, session());

        InOrder order = inOrder(transportService, deliveryCoordinator);
        order.verify(transportService).send(
                eq(OpenAdrOperations.CREATED_REPORT_RESPONSE),
                any(OadrCreatedReportType.class),
                eq(session())
        );
        order.verify(deliveryCoordinator).deliverOneShot(first, session());
        order.verify(deliveryCoordinator).deliverOneShot(second, session());
    }

    @Test
    void r1_3060_processesPiggybackRequestFromRegisteredReport() {
        OadrReportRequestType requestPayload = new OadrReportRequestType();
        var registeredReport = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder("REGISTER", 200, "VEN-1")
                .addReportRequest(requestPayload)
                .build();
        List<ValidatedReportRequest> validated = List.of(
                validated("PIGGYBACK-REQUEST", Duration.ofMinutes(1))
        );
        when(requestValidator.validateAll(
                registeredReport.getOadrReportRequest(),
                "REGISTER"
        )).thenReturn(validated);
        when(requestStore.activateAll(validated, NOW))
                .thenReturn(List.of(persisted("PIGGYBACK-REQUEST")));
        when(requestStore.findAllPendingReportRequestIds())
                .thenReturn(List.of("PIGGYBACK-REQUEST"));
        ArgumentCaptor<OadrCreatedReportType> response =
                ArgumentCaptor.forClass(OadrCreatedReportType.class);

        handler.handleRegisteredReport(registeredReport, session());

        verify(transportService).send(
                eq(OpenAdrOperations.CREATED_REPORT_RESPONSE),
                response.capture(),
                eq(session())
        );
        assertThat(response.getValue().getEiResponse().getRequestID())
                .isEqualTo("REGISTER");
        assertThat(response.getValue().getOadrPendingReports().getReportRequestID())
                .containsExactly("PIGGYBACK-REQUEST");
    }

    private ValidatedReportRequest validated(
            String reportRequestId,
            Duration reportBackDuration
    ) {
        return new ValidatedReportRequest(
                reportRequestId,
                ReportService.statusReportSpecifierId(1),
                "TELEMETRY_STATUS",
                "RESOURCE-1",
                Set.of(ReportService.RID_RESOURCE_STATUS),
                Duration.ofMinutes(1),
                reportBackDuration,
                NOW,
                Duration.ZERO,
                false
        );
    }

    private ReportRequest persisted(String reportRequestId) {
        ReportRequest request = new ReportRequest();
        request.setReportRequestId(reportRequestId);
        return request;
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
