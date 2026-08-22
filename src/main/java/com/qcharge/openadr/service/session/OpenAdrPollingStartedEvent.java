package com.qcharge.openadr.service.session;

import java.time.Duration;
import java.util.Objects;

/** Requests that polling starts for the newly registered session. */
public record OpenAdrPollingStartedEvent(Duration pollFrequency) {

    public OpenAdrPollingStartedEvent {
        Objects.requireNonNull(pollFrequency, "pollFrequency");
    }
}
