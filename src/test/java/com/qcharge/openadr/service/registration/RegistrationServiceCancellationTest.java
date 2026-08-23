package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.event.execution.EventExecutionCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
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
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceCancellationTest {

    @Mock OpenAdrProperties properties;
    @Mock VenRegistrationRepository registrationRepository;
    @Mock VenRegistrationStateService registrationStateService;
    @Mock VtnTransportService transportService;
    @Mock EventExecutionCoordinator eventExecutionCoordinator;
    @Mock OpenAdrSessionProvider sessionProvider;
    @Mock ApplicationEventPublisher eventPublisher;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(
                properties,
                registrationRepository,
                registrationStateService,
                transportService,
                eventExecutionCoordinator,
                sessionProvider,
                eventPublisher
        );
    }

    @Test
    void localCancellationReservesBeforeSendingAndCompletesAfterSuccess() {
        OpenAdrSessionSnapshot session = registeredSession();
        doReturn(new OadrCanceledPartyRegistrationType())
                .when(transportService)
                .send(
                        same(OpenAdrOperations.CANCEL_PARTY_REGISTRATION),
                        any(OadrCancelPartyRegistrationType.class),
                        same(session)
                );

        service.performCancelRegistration(session);

        InOrder order = inOrder(
                registrationStateService,
                transportService,
                eventExecutionCoordinator
        );
        order.verify(registrationStateService).beginCancellation(session);
        order.verify(transportService).send(
                same(OpenAdrOperations.CANCEL_PARTY_REGISTRATION),
                any(OadrCancelPartyRegistrationType.class),
                same(session)
        );
        order.verify(eventExecutionCoordinator)
                .clearDownstreamForRegistrationCancellation();
        order.verify(registrationStateService).completeCancellation(session);
    }

    @Test
    void localCancellationRemainsIncompleteWhenTransportFails() {
        OpenAdrSessionSnapshot session = registeredSession();
        RuntimeException failure = new RuntimeException("transport failed");
        doThrow(failure)
                .when(transportService)
                .send(
                        same(OpenAdrOperations.CANCEL_PARTY_REGISTRATION),
                        any(OadrCancelPartyRegistrationType.class),
                        same(session)
                );

        assertThrows(
                RuntimeException.class,
                () -> service.performCancelRegistration(session)
        );

        verify(registrationStateService).beginCancellation(session);
        verify(eventExecutionCoordinator, never())
                .clearDownstreamForRegistrationCancellation();
        verify(registrationStateService, never()).completeCancellation(session);
    }

    @Test
    void validRemoteCancellationIsReservedWithoutSendingSuccessEarly() {
        OpenAdrSessionSnapshot session = registeredSession();
        OadrCancelPartyRegistrationType request = remoteCancellationRequest();
        when(
                registrationStateService.hasCancellableRegistration(session)
        ).thenReturn(true);
        when(
                registrationStateService.tryBeginCancellation(session)
        ).thenReturn(true);

        RemoteCancellationDecision decision =
                service.prepareRemoteCancellation(request, session);

        assertEquals(RemoteCancellationDecision.ACCEPTED, decision);
        verify(registrationStateService).tryBeginCancellation(session);
        verify(transportService, never()).send(
                same(OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE),
                any(OadrCanceledPartyRegistrationType.class),
                same(session)
        );
    }

    @Test
    void invalidRemoteCancellationReturns452WithoutChangingState() {
        OpenAdrSessionSnapshot session = registeredSession();
        OadrCancelPartyRegistrationType request = remoteCancellationRequest();
        request.setRegistrationID("REG-OTHER");
        when(
                registrationStateService.hasCancellableRegistration(session)
        ).thenReturn(true);
        doReturn(null).when(transportService).send(
                same(OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE),
                any(OadrCanceledPartyRegistrationType.class),
                same(session)
        );

        RemoteCancellationDecision decision =
                service.prepareRemoteCancellation(request, session);

        assertEquals(RemoteCancellationDecision.REJECTED_INVALID_ID, decision);
        ArgumentCaptor<OadrCanceledPartyRegistrationType> responseCaptor =
                ArgumentCaptor.forClass(OadrCanceledPartyRegistrationType.class);
        verify(transportService).send(
                same(OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE),
                responseCaptor.capture(),
                same(session)
        );
        assertEquals(
                "452",
                responseCaptor.getValue().getEiResponse().getResponseCode()
        );
        assertEquals(
                "REQ-1",
                responseCaptor.getValue().getEiResponse().getRequestID()
        );
        verify(registrationStateService, never()).tryBeginCancellation(session);
    }

    @Test
    void missingRemoteRegistrationIsIgnoredWithoutResponse() {
        OpenAdrSessionSnapshot session = registeredSession();
        OadrCancelPartyRegistrationType request = remoteCancellationRequest();

        RemoteCancellationDecision decision =
                service.prepareRemoteCancellation(request, session);

        assertEquals(RemoteCancellationDecision.IGNORED_NOT_REGISTERED, decision);
        verify(transportService, never()).send(
                same(OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE),
                any(OadrCanceledPartyRegistrationType.class),
                same(session)
        );
        verify(registrationStateService, never()).tryBeginCancellation(session);
    }

    @Test
    void remoteSuccessAcknowledgementEchoesRequestAfterCompletion() {
        OpenAdrSessionSnapshot session = registeredSession();
        OadrCancelPartyRegistrationType request = remoteCancellationRequest();
        doReturn(null).when(transportService).send(
                same(OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE),
                any(OadrCanceledPartyRegistrationType.class),
                same(session)
        );

        service.acknowledgeRemoteCancellation(request, session);

        ArgumentCaptor<OadrCanceledPartyRegistrationType> responseCaptor =
                ArgumentCaptor.forClass(OadrCanceledPartyRegistrationType.class);
        verify(transportService).send(
                same(OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE),
                responseCaptor.capture(),
                same(session)
        );
        assertEquals(
                "200",
                responseCaptor.getValue().getEiResponse().getResponseCode()
        );
        assertEquals(
                "REQ-1",
                responseCaptor.getValue().getEiResponse().getRequestID()
        );
    }

    private OadrCancelPartyRegistrationType remoteCancellationRequest() {
        OadrCancelPartyRegistrationType request =
                new OadrCancelPartyRegistrationType();
        request.setRequestID("REQ-1");
        request.setRegistrationID("REG-1");
        request.setVenID("VEN-1");
        return request;
    }

    private OpenAdrSessionSnapshot registeredSession() {
        return new OpenAdrSessionSnapshot(
                1L,
                7L,
                "VEN-1",
                "VTN-1",
                "REG-1",
                Duration.ofSeconds(10)
        );
    }
}
