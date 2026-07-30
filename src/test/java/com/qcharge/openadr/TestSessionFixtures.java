package com.qcharge.openadr;

import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;

import java.time.Duration;

public final class TestSessionFixtures {

    private TestSessionFixtures() {
    }

    public static OpenAdrSessionSnapshot registeredSession() {
        return registeredSession("TH_VEN", "test-vtn", "registration-1");
    }

    public static OpenAdrSessionSnapshot registeredSession(
            String venId,
            String vtnId,
            String registrationId
    ) {
        return new OpenAdrSessionSnapshot(
                1L, venId, vtnId, registrationId, Duration.ofSeconds(60)
        );
    }

    public static OpenAdrSessionSnapshot bootstrapSession() {
        return bootstrapSession("TH_VEN", "test-vtn");
    }

    public static OpenAdrSessionSnapshot bootstrapSession(
            String venId,
            String vtnId
    ) {
        return new OpenAdrSessionSnapshot(
                null,
                venId,
                vtnId,
                null,
                Duration.ofSeconds(60)
        );
    }
}
