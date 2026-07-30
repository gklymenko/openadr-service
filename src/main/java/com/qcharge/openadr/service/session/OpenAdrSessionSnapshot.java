package com.qcharge.openadr.service.session;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable identity and polling state captured for one OpenADR exchange.
 *
 * <p>A null {@code registrationEntityId} and {@code registrationId} identify
 * the bootstrap state before the first successful registration.</p>
 */
public record OpenAdrSessionSnapshot(
        Long registrationEntityId,
        String venId,
        String vtnId,
        String registrationId,
        Duration pollFrequency
) {

    public OpenAdrSessionSnapshot {
        if (venId == null || venId.isBlank()) {
            throw new IllegalArgumentException("venId must not be blank");
        }
        Objects.requireNonNull(pollFrequency, "pollFrequency");
        if (pollFrequency.isZero() || pollFrequency.isNegative()) {
            throw new IllegalArgumentException("pollFrequency must be positive");
        }
    }

    public boolean registered() {
        return registrationEntityId != null
                && registrationId != null
                && !registrationId.isBlank();
    }
}
