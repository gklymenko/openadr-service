package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.event.execution.EventExecutionCoordinator;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceCancellationTest {

    @Mock OpenAdrProperties properties;
    @Mock VenRegistrationRepository registrationRepository;
    @Mock VenRegistrationStateService registrationStateService;
    @Mock VtnTransportService transportService;
    @Mock EventProtocolAdapter eventProtocolAdapter;
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
                eventProtocolAdapter,
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
    void acceptedRemoteCancellationReservesBeforeAcknowledgement() {
        OpenAdrSessionSnapshot session = registeredSession();
        OadrCancelPartyRegistrationType request =
                new OadrCancelPartyRegistrationType();
        request.setRequestID("REQ-1");
        request.setRegistrationID("REG-1");
        request.setVenID("VEN-1");
        doReturn(null)
                .when(transportService)
                .send(
                        same(OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE),
                        any(OadrCanceledPartyRegistrationType.class),
                        same(session)
                );

        service.acknowledgeCancelPartyRegistration(request, session);

        InOrder order = inOrder(registrationStateService, transportService);
        order.verify(registrationStateService).beginCancellation(session);
        order.verify(transportService).send(
                same(OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE),
                any(OadrCanceledPartyRegistrationType.class),
                same(session)
        );
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
