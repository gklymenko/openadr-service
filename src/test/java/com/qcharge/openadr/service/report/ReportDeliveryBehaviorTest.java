package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportDeliveryBehaviorTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    @Mock ReportRequestStore requestStore;
    @Mock TelemetryReportFactory telemetryReportFactory;
    @Mock ReportService reportService;
    @Mock VtnTransportService transportService;
    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    private ReportDeliveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new ReportDeliveryCoordinator(
                requestStore,
                telemetryReportFactory,
                reportService,
                transportService,
                lifecycleCoordinator,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void r1_3040_basicCancellationReturnsAllRemainingPendingReports() {
        ReportRequest cancelled = periodicRequest("CANCELLED-REQUEST");
        when(requestStore.beginCancellation(List.of("CANCELLED-REQUEST"), false))
                .thenReturn(new ReportRequestStore.CancellationBatch(
                        List.of(cancelled),
                        List.of()
                ));
        when(requestStore.findAllPendingReportRequestIds())
                .thenReturn(List.of("STILL-PENDING"));
        ArgumentCaptor<OadrCanceledReportType> response =
                ArgumentCaptor.forClass(OadrCanceledReportType.class);

        coordinator.handleStandaloneCancellation(
                Oadr20bEiReportBuilders
                        .newOadr20bCancelReportBuilder("CANCEL", "VEN-1", false)
                        .addReportRequestId("CANCELLED-REQUEST")
                        .build(),
                session()
        );

        verify(transportService).send(
                eq(OpenAdrOperations.CANCELED_REPORT_RESPONSE),
                response.capture(),
                eq(session())
        );
        assertThat(response.getValue().getEiResponse().getResponseCode()).isEqualTo("200");
        assertThat(response.getValue().getOadrPendingReports().getReportRequestID())
                .containsExactly("STILL-PENDING");
        verify(telemetryReportFactory, never()).periodic(any(), any(), any(), any());
    }

    @Test
    void r1_3045_acknowledgesPendingBeforeDeliveringFinalReport() {
        ReportRequest request = periodicRequest("FINAL-REQUEST");
        request.setStatus(ReportRequest.Status.FINAL_REPORT_PENDING);
        ReportRequestStore.DeliveryClaim claim = claim(request);
        when(requestStore.beginCancellation(List.of("FINAL-REQUEST"), true))
                .thenReturn(new ReportRequestStore.CancellationBatch(
                        List.of(request),
                        List.of()
                ));
        when(requestStore.findAllPendingReportRequestIds())
                .thenReturn(List.of("FINAL-REQUEST"));
        when(requestStore.claimFinal("FINAL-REQUEST", NOW))
                .thenReturn(Optional.of(claim));
        when(telemetryReportFactory.periodic(any(), any(), any(), eq(NOW)))
                .thenReturn(new OadrReportType());
        OadrUpdatedReportType updated = Oadr20bEiReportBuilders
                .newOadr20bUpdatedReportBuilder("UPDATE", 200, "VEN-1")
                .build();
        doAnswer(invocation -> OpenAdrOperations.UPDATE_REPORT.equals(
                invocation.getArgument(0)
        ) ? updated : null).when(transportService).send(
                any(),
                any(),
                eq(session())
        );

        coordinator.handleStandaloneCancellation(
                Oadr20bEiReportBuilders
                        .newOadr20bCancelReportBuilder("CANCEL", "VEN-1", true)
                        .addReportRequestId("FINAL-REQUEST")
                        .build(),
                session()
        );

        InOrder order = inOrder(transportService, requestStore);
        order.verify(transportService).send(
                eq(OpenAdrOperations.CANCELED_REPORT_RESPONSE),
                any(OadrCanceledReportType.class),
                eq(session())
        );
        order.verify(transportService).send(
                eq(OpenAdrOperations.UPDATE_REPORT),
                any(),
                eq(session())
        );
        order.verify(requestStore).completeFinalCancellation(claim);
    }

    @Test
    void r1_3050_appliesPiggybackCancellationWithoutCanceledReportResponse() {
        ReportRequest request = periodicRequest("PIGGYBACK-REQUEST");
        ReportRequest cancelledRequest = periodicRequest("CANCELLED-BY-PIGGYBACK");
        ReportRequestStore.DeliveryClaim claim = claim(request);
        when(requestStore.claimActive("PIGGYBACK-REQUEST", NOW))
                .thenReturn(Optional.of(claim));
        when(telemetryReportFactory.oneShot(request))
                .thenReturn(new OadrReportType());
        var cancellation = Oadr20bEiReportBuilders
                .newOadr20bCancelReportBuilder("PIGGYBACK-CANCEL", "VEN-1", false)
                .addReportRequestId("CANCELLED-BY-PIGGYBACK")
                .build();
        OadrUpdatedReportType updated = Oadr20bEiReportBuilders
                .newOadr20bUpdatedReportBuilder("UPDATE", 200, "VEN-1")
                .withOadrCancelReport(cancellation)
                .build();
        doReturn(updated).when(transportService).send(
                eq(OpenAdrOperations.UPDATE_REPORT),
                any(OadrUpdateReportType.class),
                eq(session())
        );
        when(requestStore.beginCancellation(
                List.of("CANCELLED-BY-PIGGYBACK"),
                false
        )).thenReturn(new ReportRequestStore.CancellationBatch(
                List.of(cancelledRequest),
                List.of()
        ));

        coordinator.deliverOneShot(request, session());

        InOrder order = inOrder(transportService, requestStore);
        order.verify(transportService).send(
                eq(OpenAdrOperations.UPDATE_REPORT),
                any(OadrUpdateReportType.class),
                eq(session())
        );
        order.verify(requestStore).completeDelivery(claim);
        order.verify(requestStore).beginCancellation(
                List.of("CANCELLED-BY-PIGGYBACK"),
                false
        );
        verify(transportService, never()).send(
                eq(OpenAdrOperations.CANCELED_REPORT_RESPONSE),
                any(OadrCanceledReportType.class),
                eq(session())
        );
    }

    @Test
    void r1_3170_successfulMetadataDeliveryImplicitlyCancelsTelemetryReports() {
        ReportRequest metadata = new ReportRequest();
        metadata.setReportRequestId("METADATA-REQUEST");
        metadata.setReportSpecifierId(ReportService.REPORT_SPECIFIER_ID_METADATA);
        ReportRequestStore.DeliveryClaim claim = claim(metadata);
        when(requestStore.claimActive("METADATA-REQUEST", NOW))
                .thenReturn(Optional.of(claim));
        OadrRegisterReportType registerReport = Oadr20bEiReportBuilders
                .newOadr20bRegisterReportBuilder("REGISTER", "VEN-1")
                .build();
        when(reportService.buildMetadataRegisterReport("METADATA-REQUEST", session()))
                .thenReturn(registerReport);

        coordinator.deliverOneShot(metadata, session());

        InOrder order = inOrder(transportService, requestStore);
        order.verify(transportService).send(
                OpenAdrOperations.REGISTER_REPORT,
                registerReport,
                session()
        );
        order.verify(requestStore).cancelNonMetadataRequests();
        order.verify(requestStore).completeDelivery(claim);
    }

    private ReportRequest periodicRequest(String requestId) {
        ReportRequest request = new ReportRequest();
        request.setReportRequestId(requestId);
        request.setReportSpecifierId(ReportService.REPORT_SPECIFIER_ID_TELEMETRY_STATUS);
        request.setRequestedRids(ReportService.RID_RESOURCE_STATUS);
        request.setGranularitySeconds(60);
        request.setReportBackDurationSeconds(60);
        request.setRequestedStart(NOW.minus(Duration.ofMinutes(5)));
        request.setRequestedDurationSeconds(0L);
        request.setLastReportedAt(NOW.minus(Duration.ofMinutes(1)));
        request.setStatus(ReportRequest.Status.ACTIVE);
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

    private ReportRequestStore.DeliveryClaim claim(ReportRequest request) {
        return new ReportRequestStore.DeliveryClaim(request, "DELIVERY-TOKEN");
    }
}
