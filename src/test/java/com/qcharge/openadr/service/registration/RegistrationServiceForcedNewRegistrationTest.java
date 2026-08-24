package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.xcal.DurationPropType;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceForcedNewRegistrationTest {

    @Mock VenRegistrationRepository registrationRepository;
    @Mock VenRegistrationStateService registrationStateService;
    @Mock VtnTransportService transportService;
    @Mock EventExecutionCoordinator eventExecutionCoordinator;
    @Mock OpenAdrSessionProvider sessionProvider;
    @Mock ApplicationEventPublisher eventPublisher;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getVen().setId("CONFIGURED-VEN");

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
    void forcedNewRegistrationOmitsBothIdsAndCreatesNewInstance() {
        VenRegistration previous = existingRegistration();
        OpenAdrSessionSnapshot bootstrapSession = bootstrapSession();
        OpenAdrSessionSnapshot registeredSession = registeredSession();
        OadrCreatedPartyRegistrationType response = successfulResponse();

        when(registrationRepository.findFirstByStatusOrderByUpdatedAtDesc(
                VenRegistrationStatus.REGISTERED
        )).thenReturn(Optional.of(previous));
        when(sessionProvider.bootstrap()).thenReturn(bootstrapSession);
        doReturn(response).when(transportService).send(
                same(OpenAdrOperations.CREATE_PARTY_REGISTRATION),
                any(OadrCreatePartyRegistrationType.class),
                same(bootstrapSession)
        );
        when(registrationRepository.save(any(VenRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionProvider.fromRegistration(any(VenRegistration.class)))
                .thenReturn(registeredSession);

        OpenAdrSessionSnapshot result = service.performForcedNewRegistration();

        ArgumentCaptor<OadrCreatePartyRegistrationType> requestCaptor =
                ArgumentCaptor.forClass(OadrCreatePartyRegistrationType.class);
        verify(transportService).send(
                same(OpenAdrOperations.CREATE_PARTY_REGISTRATION),
                requestCaptor.capture(),
                same(bootstrapSession)
        );

        assertNull(requestCaptor.getValue().getVenID());
        assertNull(requestCaptor.getValue().getRegistrationID());
        assertEquals(VenRegistrationStatus.CANCELLED, previous.getStatus());
        verify(registrationStateService).clearDependentRegistrationData();
        verify(eventPublisher).publishEvent(
                new PostRegistrationBootstrapEvent(registeredSession)
        );
        assertSame(registeredSession, result);
    }

    private VenRegistration existingRegistration() {
        VenRegistration registration = new VenRegistration();
        registration.setId(1L);
        registration.setVenId("VEN-1");
        registration.setVtnId("VTN-1");
        registration.setRegistrationId("REG-1");
        registration.setStatus(VenRegistrationStatus.REGISTERED);
        registration.setRequestedPollFrequency("PT10S");
        registration.setRegisteredAt(Instant.parse("2026-08-23T10:00:00Z"));
        registration.setUpdatedAt(Instant.parse("2026-08-23T10:00:00Z"));
        return registration;
    }

    private OpenAdrSessionSnapshot bootstrapSession() {
        return new OpenAdrSessionSnapshot(
                null,
                8L,
                "CONFIGURED-VEN",
                "VTN-1",
                null,
                Duration.ofSeconds(10)
        );
    }

    private OpenAdrSessionSnapshot registeredSession() {
        return new OpenAdrSessionSnapshot(
                2L,
                9L,
                "VEN-1",
                "VTN-1",
                "REG-1",
                Duration.ofSeconds(10)
        );
    }

    private OadrCreatedPartyRegistrationType successfulResponse() {
        OadrCreatedPartyRegistrationType response =
                new OadrCreatedPartyRegistrationType();
        // Rule 406 permits the VTN to reuse the previous protocol IDs even
        // though this is a completely new registration instance.
        response.setVenID("VEN-1");
        response.setVtnID("VTN-1");
        response.setRegistrationID("REG-1");

        DurationPropType pollFrequency = new DurationPropType();
        pollFrequency.setDuration("PT10S");
        response.setOadrRequestedOadrPollFreq(pollFrequency);
        return response;
    }
}
