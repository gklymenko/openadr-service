package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;

import java.util.Objects;

public record PostRegistrationBootstrapEvent(
        OpenAdrSessionSnapshot session
) {
    public PostRegistrationBootstrapEvent {
        Objects.requireNonNull(session, "session");
    }
}
