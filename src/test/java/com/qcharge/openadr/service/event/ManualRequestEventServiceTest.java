package com.qcharge.openadr.service.event;

import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualRequestEventServiceTest {

    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;
    @Mock EventPoller eventPoller;
    @Mock RegistrationService registrationService;
    @Mock TaskScheduler taskScheduler;
    @Mock ScheduledFuture<?> scheduledFuture;

    private ManualRequestEventService service;

    @BeforeEach
    void setUp() {
        service = new ManualRequestEventService(
                lifecycleCoordinator,
                eventPoller,
                registrationService,
                taskScheduler
        );
    }

    @Test
    void queuesRequestAndExecutesItExclusivelyWithPolling() {
        OpenAdrSessionSnapshot session = registeredSession();
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

        when(lifecycleCoordinator.requireRegisteredSession()).thenReturn(session);
        when(lifecycleCoordinator.isActive(session)).thenReturn(true);
        doReturn(scheduledFuture)
                .when(taskScheduler)
                .schedule(taskCaptor.capture(), any(Instant.class));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(eventPoller).executeExclusivelyWithPolling(any(Runnable.class));

        String requestId = service.requestEvents();

        assertDoesNotThrow(() -> UUID.fromString(requestId));
        verify(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        taskCaptor.getValue().run();

        verify(eventPoller).executeExclusivelyWithPolling(any(Runnable.class));
        verify(registrationService).requestAllEvents(session, requestId);
    }

    @Test
    void skipsQueuedRequestWhenSessionIsNoLongerActive() {
        OpenAdrSessionSnapshot session = registeredSession();
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);

        when(lifecycleCoordinator.requireRegisteredSession()).thenReturn(session);
        when(lifecycleCoordinator.isActive(session)).thenReturn(false);
        doReturn(scheduledFuture)
                .when(taskScheduler)
                .schedule(taskCaptor.capture(), any(Instant.class));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(eventPoller).executeExclusivelyWithPolling(any(Runnable.class));

        service.requestEvents();
        taskCaptor.getValue().run();

        verify(registrationService, never())
                .requestAllEvents(any(), any());
    }

    private OpenAdrSessionSnapshot registeredSession() {
        return new OpenAdrSessionSnapshot(
                1L,
                4L,
                "VEN-1",
                "VTN-1",
                "REGISTRATION-1",
                Duration.ofSeconds(10)
        );
    }
}
