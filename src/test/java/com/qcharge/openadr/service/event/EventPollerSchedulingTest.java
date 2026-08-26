package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.registration.RegistrationMessageHandler;
import com.qcharge.openadr.service.report.ReportCommandQueue;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPollerSchedulingTest {

    @Mock OpenAdrProperties properties;
    @Mock OpenAdrProperties.Transport transportProperties;
    @Mock VtnTransportService transportService;
    @Mock EventProtocolAdapter eventProtocolAdapter;
    @Mock ReportCommandQueue reportCommandQueue;
    @Mock TaskScheduler taskScheduler;
    @Mock OpenAdrApplicationErrorMapper applicationErrorMapper;
    @Mock OpenAdrReplyFactory replyFactory;
    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;
    @Mock RegistrationMessageHandler registrationMessageHandler;
    @Mock ScheduledFuture<?> firstTask;
    @Mock ScheduledFuture<?> secondTask;
    @Mock TriggerContext triggerContext;

    private EventPoller eventPoller;

    @BeforeEach
    void setUp() {
        eventPoller = new EventPoller(
                properties,
                transportService,
                eventProtocolAdapter,
                reportCommandQueue,
                taskScheduler,
                applicationErrorMapper,
                replyFactory,
                lifecycleCoordinator,
                registrationMessageHandler
        );
    }

    @Test
    void triggerPollsImmediatelyThenAppliesIntervalAndPositiveJitter() {
        doReturn(firstTask).when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));
        when(properties.getTransport()).thenReturn(transportProperties);
        when(transportProperties.getMaxPollJitterSeconds()).thenReturn(5);

        eventPoller.start(Duration.ofSeconds(30));

        ArgumentCaptor<Trigger> triggerCaptor =
                ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler).schedule(
                any(Runnable.class),
                triggerCaptor.capture()
        );

        Trigger trigger = triggerCaptor.getValue();
        Instant initialExecution = Instant.parse("2026-08-24T09:00:00Z");
        when(triggerContext.getClock()).thenReturn(
                Clock.fixed(initialExecution, ZoneOffset.UTC)
        );
        assertThat(trigger.nextExecution(triggerContext))
                .isEqualTo(initialExecution);

        Instant lastCompletion = Instant.parse("2026-08-24T10:00:00Z");
        when(triggerContext.lastCompletion()).thenReturn(lastCompletion);

        assertThat(trigger.nextExecution(triggerContext))
                .isBetween(
                        lastCompletion.plusSeconds(30),
                        lastCompletion.plusSeconds(35)
                );
    }

    @Test
    void restartCancelsPreviousPeriodicTask() {
        doReturn(firstTask, secondTask).when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        eventPoller.start(Duration.ofSeconds(30));
        eventPoller.start(Duration.ofSeconds(60));

        verify(firstTask).cancel(false);
        verify(secondTask, never()).cancel(false);
    }

    @Test
    void stopCancelsPeriodicTask() {
        doReturn(firstTask).when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        eventPoller.start(Duration.ofSeconds(30));
        eventPoller.stop();

        verify(firstTask).cancel(false);
    }

    @Test
    void rejectsNonPositivePollInterval() {
        assertThatThrownBy(() -> eventPoller.start(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenADR poll interval must be positive");

        assertThatThrownBy(() -> eventPoller.start(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OpenADR poll interval must be positive");

        verify(taskScheduler, never()).schedule(
                any(Runnable.class),
                any(Trigger.class)
        );
    }
}
