package com.qcharge.openadr.service.session;

import org.apache.logging.log4j.util.Strings;

import java.time.Duration;
import java.util.Objects;

import static com.qcharge.openadr.ApiMessage.BLANK_VENID_ERROR;

/**
 * Immutable identity and polling state captured for one OpenADR exchange.
 *
 * <p>A null {@code registrationEntityId} and {@code registrationId} identify
 * the bootstrap state before the first successful registration.</p>
 */
public record OpenAdrSessionSnapshot(
        Long registrationEntityId,
        long generation,
        String venId,
        String vtnId,
        String registrationId,
        Duration pollFrequency
) {

    public OpenAdrSessionSnapshot {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        if (Strings.isBlank(venId)) {
            throw new IllegalArgumentException(BLANK_VENID_ERROR.getMessage());
        }
        Objects.requireNonNull(pollFrequency, "pollFrequency");
        if (pollFrequency.isZero() || pollFrequency.isNegative()) {
            throw new IllegalArgumentException("pollFrequency must be positive");
        }
    }

    public boolean registered() {
        return registrationEntityId != null && Strings.isNotBlank(registrationId);
    }
}
