package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.repository.OptScheduleRepository;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.qcharge.openadr.model.enums.VenRegistrationStatus.CANCELLED;
import static com.qcharge.openadr.model.enums.VenRegistrationStatus.CANCELLING;
import static com.qcharge.openadr.model.enums.VenRegistrationStatus.REGISTERED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenRegistrationStateServiceTest {

    @Mock VenRegistrationRepository registrationRepository;
    @Mock DrEventRepository eventRepository;
    @Mock VenReportRepository venReportRepository;
    @Mock OptScheduleRepository optScheduleRepository;

    private VenRegistrationStateService service;

    @BeforeEach
    void setUp() {
        service = new VenRegistrationStateService(
                registrationRepository,
                eventRepository,
                venReportRepository,
                optScheduleRepository
        );
    }

    @Test
    void beginCancellationAtomicallyReservesExactRegistration() {
        OpenAdrSessionSnapshot session = registeredSession();
        when(registrationRepository.transitionStatus(
                eq(1L),
                eq("VEN-1"),
                eq("REG-1"),
                eq(REGISTERED),
                eq(CANCELLING),
                any(Instant.class)
        )).thenReturn(1);

        service.beginCancellation(session);

        verify(registrationRepository).transitionStatus(
                eq(1L),
                eq("VEN-1"),
                eq("REG-1"),
                eq(REGISTERED),
                eq(CANCELLING),
                any(Instant.class)
        );
        verify(registrationRepository, never()).findById(1L);
        verify(eventRepository, never()).deleteAll();
    }

    @Test
    void repeatedBeginCancellationIsIdempotent() {
        OpenAdrSessionSnapshot session = registeredSession();
        VenRegistration registration = registration(CANCELLING);
        when(registrationRepository.findById(1L)).thenReturn(Optional.of(registration));

        assertDoesNotThrow(() -> service.beginCancellation(session));
    }

    @Test
    void beginCancellationRejectsUnexpectedPersistedState() {
        OpenAdrSessionSnapshot session = registeredSession();
        VenRegistration registration = registration(CANCELLED);
        when(registrationRepository.findById(1L)).thenReturn(Optional.of(registration));

        assertThrows(
                IllegalStateException.class,
                () -> service.beginCancellation(session)
        );
    }

    @Test
    void completeCancellationUpdatesStateAndClearsRegistrationData() {
        OpenAdrSessionSnapshot session = registeredSession();
        VenRegistration registration = registration(CANCELLING);
        when(registrationRepository.findById(1L)).thenReturn(Optional.of(registration));

        service.completeCancellation(session);

        assertEquals(CANCELLED, registration.getStatus());
        verify(venReportRepository).deleteAll();
        verify(optScheduleRepository).deleteAll();
        verify(eventRepository).deleteAll();
        verify(registrationRepository, never()).save(registration);
    }

    @Test
    void completeCancellationRejectsRegistrationThatDoesNotMatchSession() {
        OpenAdrSessionSnapshot session = registeredSession();
        VenRegistration registration = registration(CANCELLING);
        registration.setRegistrationId("REG-OTHER");
        when(registrationRepository.findById(1L)).thenReturn(Optional.of(registration));

        assertThrows(
                IllegalStateException.class,
                () -> service.completeCancellation(session)
        );

        verify(venReportRepository, never()).deleteAll();
        verify(optScheduleRepository, never()).deleteAll();
        verify(eventRepository, never()).deleteAll();
    }

    @Test
    void repeatedCompletionIsIdempotentAndCompletesCleanup() {
        OpenAdrSessionSnapshot session = registeredSession();
        VenRegistration registration = registration(CANCELLED);
        when(registrationRepository.findById(1L)).thenReturn(Optional.of(registration));

        service.completeCancellation(session);

        assertEquals(CANCELLED, registration.getStatus());
        verify(venReportRepository).deleteAll();
        verify(optScheduleRepository).deleteAll();
        verify(eventRepository).deleteAll();
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

    private VenRegistration registration(VenRegistrationStatus status) {
        VenRegistration registration = new VenRegistration();
        registration.setId(1L);
        registration.setVenId("VEN-1");
        registration.setVtnId("VTN-1");
        registration.setRegistrationId("REG-1");
        registration.setStatus(status);
        return registration;
    }
}
