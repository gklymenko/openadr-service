package com.qcharge.openadr.service.session;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAdrSessionProvider {

    private final OpenAdrProperties properties;
    private final VenRegistrationRepository registrationRepository;

    public OpenAdrSessionSnapshot current() {
        return registrationRepository
                .findFirstByStatusOrderByUpdatedAtDesc(VenRegistrationStatus.REGISTERED)
                .map(this::fromRegistration)
                .orElseGet(this::bootstrap);
    }

    public OpenAdrSessionSnapshot bootstrap() {
        return new OpenAdrSessionSnapshot(
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

        return new OpenAdrSessionSnapshot(
                registration.getId(),
                venId,
                vtnId,
                registration.getRegistrationId(),
                pollFrequency(registration.getRequestedPollFrequency())
        );
    }

    private Duration pollFrequency(String persistedValue) {
        if (hasText(persistedValue)) {
            try {
                Duration duration = Duration.parse(persistedValue);
                if (!duration.isZero() && !duration.isNegative()) {
                    return duration;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Cannot parse requestedPollFrequency={} for session snapshot",
                        persistedValue,
                        exception
                );
            }
        }
        return configuredPollFrequency();
    }

    private Duration configuredPollFrequency() {
        return Duration.ofSeconds(properties.getTransport().getPollIntervalSeconds());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
