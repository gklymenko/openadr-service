package com.qcharge.openadr.service.event;

import com.qcharge.openadr.service.session.OpenAdrPollingStartedEvent;
import com.qcharge.openadr.service.session.OpenAdrPollingStoppedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Adapts session lifecycle events to the polling scheduler. */
@Component
@RequiredArgsConstructor
public class OpenAdrPollingLifecycleListener {

    private final EventPoller eventPoller;

    @EventListener
    public void startPolling(OpenAdrPollingStartedEvent event) {
        eventPoller.start(event.pollFrequency());
    }

    @EventListener
    public void stopPolling(OpenAdrPollingStoppedEvent event) {
        eventPoller.stop();
    }
}
