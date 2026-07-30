package com.qcharge.openadr.session;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAdrSessionProviderTest {

    private final VenRegistrationRepository repository =
            mock(VenRegistrationRepository.class);
    private final OpenAdrProperties properties = new OpenAdrProperties();
    private OpenAdrSessionProvider provider;

    @BeforeEach
    void setUp() {
        properties.getVen().setId("CONFIGURED-VEN");
        properties.getVtn().setId("CONFIGURED-VTN");
        properties.getTransport().setPollIntervalSeconds(30);
        provider = new OpenAdrSessionProvider(properties, repository);
    }

    @Test
    void currentUsesConfiguredBootstrapStateWithoutRegistration() {
        when(repository.findFirstByStatusOrderByUpdatedAtDesc(
                VenRegistrationStatus.REGISTERED
        )).thenReturn(Optional.empty());

        OpenAdrSessionSnapshot snapshot = provider.current();

        assertEquals("CONFIGURED-VEN", snapshot.venId());
        assertEquals("CONFIGURED-VTN", snapshot.vtnId());
        assertEquals(Duration.ofSeconds(30), snapshot.pollFrequency());
        assertFalse(snapshot.registered());
    }

    @Test
    void currentCapturesAllFieldsFromOneActiveRegistration() {
        VenRegistration registration = new VenRegistration();
        registration.setId(42L);
        registration.setVenId("ASSIGNED-VEN");
        registration.setVtnId("ACTIVE-VTN");
        registration.setRegistrationId("REG-42");
        registration.setRequestedPollFrequency("PT45S");

        when(repository.findFirstByStatusOrderByUpdatedAtDesc(
                VenRegistrationStatus.REGISTERED
        )).thenReturn(Optional.of(registration));

        OpenAdrSessionSnapshot snapshot = provider.current();

        assertEquals(42L, snapshot.registrationEntityId());
        assertEquals("ASSIGNED-VEN", snapshot.venId());
        assertEquals("ACTIVE-VTN", snapshot.vtnId());
        assertEquals("REG-42", snapshot.registrationId());
        assertEquals(Duration.ofSeconds(45), snapshot.pollFrequency());
        assertTrue(snapshot.registered());
    }

    @Test
    void invalidPersistedPollFrequencyUsesConfiguredFallback() {
        VenRegistration registration = new VenRegistration();
        registration.setId(42L);
        registration.setVenId("ASSIGNED-VEN");
        registration.setRegistrationId("REG-42");
        registration.setRequestedPollFrequency("invalid");

        OpenAdrSessionSnapshot snapshot = provider.fromRegistration(registration);

        assertEquals(Duration.ofSeconds(30), snapshot.pollFrequency());
    }

    @Test
    void generationIsStableForSamePersistedRegistrationVersion() {
        VenRegistration registration = registration(
                Instant.parse("2026-07-30T08:00:00Z")
        );

        OpenAdrSessionSnapshot first = provider.fromRegistration(registration);
        OpenAdrSessionSnapshot second = provider.fromRegistration(registration);

        assertEquals(first.generation(), second.generation());
    }

    @Test
    void generationAdvancesWhenPersistedRegistrationChanges() {
        VenRegistration registration = registration(
                Instant.parse("2026-07-30T08:00:00Z")
        );

        OpenAdrSessionSnapshot first = provider.fromRegistration(registration);
        registration.setRegistrationId("REG-43");
        registration.setUpdatedAt(
                Instant.parse("2026-07-30T08:01:00Z")
        );

        OpenAdrSessionSnapshot second = provider.fromRegistration(registration);

        assertEquals(first.generation() + 1, second.generation());
    }

    private VenRegistration registration(Instant updatedAt) {
        VenRegistration registration = new VenRegistration();
        registration.setId(42L);
        registration.setVenId("ASSIGNED-VEN");
        registration.setVtnId("ACTIVE-VTN");
        registration.setRegistrationId("REG-42");
        registration.setRequestedPollFrequency("PT45S");
        registration.setUpdatedAt(updatedAt);
        return registration;
    }
}
