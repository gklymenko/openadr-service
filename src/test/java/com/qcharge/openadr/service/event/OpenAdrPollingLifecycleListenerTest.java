package com.qcharge.openadr.service.event;

import com.qcharge.openadr.service.session.OpenAdrPollingStartedEvent;
import com.qcharge.openadr.service.session.OpenAdrPollingStoppedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OpenAdrPollingLifecycleListenerTest {

    @Mock EventPoller eventPoller;

    @InjectMocks OpenAdrPollingLifecycleListener listener;

    @Test
    void startsPollingWithPublishedFrequency() {
        Duration frequency = Duration.ofSeconds(10);

        listener.startPolling(new OpenAdrPollingStartedEvent(frequency));

        verify(eventPoller).start(frequency);
    }

    @Test
    void stopsPolling() {
        listener.stopPolling(new OpenAdrPollingStoppedEvent());

        verify(eventPoller).stop();
    }
}
