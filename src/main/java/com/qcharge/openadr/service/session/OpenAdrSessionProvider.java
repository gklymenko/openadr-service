package com.qcharge.openadr.service.session;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAdrSessionProvider {

    private final OpenAdrProperties properties;
    private final VenRegistrationRepository registrationRepository;
    private final AtomicReference<VersionedSession> versionedSession = new AtomicReference<>();

    public OpenAdrSessionSnapshot current() {
        return registrationRepository
                .findFirstByStatusOrderByUpdatedAtDesc(VenRegistrationStatus.REGISTERED)
                .map(this::fromRegistration)
                .orElseGet(this::bootstrap);
    }

    public OpenAdrSessionSnapshot bootstrap() {
        return versionedSnapshot(
                new SessionKey(null, null, null),
                null,
                properties.getVen().getId(),
                properties.getVtn().getId(),
                null,
                configuredPollFrequency()
        );
    }

    public OpenAdrSessionSnapshot fromRegistration(VenRegistration registration) {
        String venId = hasText(registration.getVenId())
                ? registration.getVenId()
                : properties.getVen().getId();
        String vtnId = hasText(registration.getVtnId())
                ? registration.getVtnId()
                : properties.getVtn().getId();

        return versionedSnapshot(
                new SessionKey(
                        registration.getId(),
                        registration.getRegistrationId(),
                        registration.getUpdatedAt()
                ),
                registration.getId(),
                venId,
                vtnId,
                registration.getRegistrationId(),
                pollFrequency(registration.getRequestedPollFrequency())
        );
    }

    private OpenAdrSessionSnapshot versionedSnapshot(
            SessionKey key, Long registrationEntityId,
            String venId, String vtnId,
            String registrationId, Duration pollFrequency
    ) {
        while (true) {
            VersionedSession current = versionedSession.get();

            if (current != null && current.key().equals(key)) {
                return current.snapshot();
            }

            long generation = current == null
                    ? 0
                    : current.snapshot().generation() + 1;

            OpenAdrSessionSnapshot candidate = new OpenAdrSessionSnapshot(
                    registrationEntityId,
                    generation,
                    venId,
                    vtnId,
                    registrationId,
                    pollFrequency
            );

            VersionedSession updated = new VersionedSession(key, candidate);

            if (versionedSession.compareAndSet(current, updated)) {
                return candidate;
            }
        }
    }

    private Duration pollFrequency(String persistedValue) {
        try {
            return Duration.parse(persistedValue);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException(
                    "Persisted requestedPollFrequency is invalid: "
                            + persistedValue,
                    exception
            );
        }
    }

    private Duration configuredPollFrequency() {
        return Duration.ofSeconds(properties.getTransport().getPollIntervalSeconds());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record SessionKey(
            Long registrationEntityId,
            String registrationId,
            Instant updatedAt
    ) {
        private SessionKey {
            if (registrationEntityId == null) {
                registrationId = null;
                updatedAt = null;
            }
        }
    }

    private record VersionedSession(
            SessionKey key,
            OpenAdrSessionSnapshot snapshot
    ) {
        private VersionedSession {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
