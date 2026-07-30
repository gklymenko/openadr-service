package com.qcharge.openadr.session;

import com.qcharge.openadr.exceptions.OpenAdrSessionUnavailableException;
import com.qcharge.openadr.exceptions.StaleOpenAdrSessionException;
import com.qcharge.openadr.service.event.EventPoller;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.session.OpenAdrSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAdrSessionLifecycleCoordinatorTest {

    @Mock OpenAdrSessionProvider sessionProvider;
    @Mock ObjectProvider<RegistrationService> registrationServiceProvider;
    @Mock ObjectProvider<EventPoller> eventPollerProvider;
    @Mock RegistrationService registrationService;
    @Mock EventPoller eventPoller;

    private OpenAdrSessionLifecycleCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(registrationServiceProvider.getObject())
                .thenReturn(registrationService);
        when(eventPollerProvider.getObject()).thenReturn(eventPoller);

        coordinator = new OpenAdrSessionLifecycleCoordinator(
                sessionProvider,
                registrationServiceProvider,
                eventPollerProvider
        );
    }

    @Test
    void twoConcurrentReregistrationsExecuteOneProtocolFlow() throws Exception {
        OpenAdrSessionSnapshot oldSession = registeredSession(7, "REG-7");
        OpenAdrSessionSnapshot newSession = registeredSession(8, "REG-8");
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);

        when(sessionProvider.current()).thenReturn(oldSession);
        when(registrationService.performReregistration(oldSession))
                .thenAnswer(invocation -> {
                    operationStarted.countDown();
                    assertTrue(allowCompletion.await(2, TimeUnit.SECONDS));
                    return newSession;
                });

        CompletableFuture<OpenAdrSessionSnapshot> first =
                CompletableFuture.supplyAsync(
                        () -> coordinator.reregister(oldSession)
                );

        assertTrue(operationStarted.await(2, TimeUnit.SECONDS));

        CompletableFuture<OpenAdrSessionSnapshot> second =
                CompletableFuture.supplyAsync(
                        () -> coordinator.reregister(oldSession)
                );

        allowCompletion.countDown();

        assertEquals(newSession, first.get(2, TimeUnit.SECONDS));
        assertEquals(newSession, second.get(2, TimeUnit.SECONDS));
        verify(registrationService, times(1))
                .performReregistration(oldSession);
        verify(eventPoller, times(1)).start(newSession.pollFrequency());
    }

    @Test
    void pollingSessionIsUnavailableDuringReregistration() throws Exception {
        OpenAdrSessionSnapshot oldSession = registeredSession(7, "REG-7");
        OpenAdrSessionSnapshot newSession = registeredSession(8, "REG-8");
        CountDownLatch operationStarted = new CountDownLatch(1);
        CountDownLatch allowCompletion = new CountDownLatch(1);

        when(sessionProvider.current()).thenReturn(oldSession);
        when(registrationService.performReregistration(oldSession))
                .thenAnswer(invocation -> {
                    operationStarted.countDown();
                    assertTrue(allowCompletion.await(2, TimeUnit.SECONDS));
                    return newSession;
                });

        CompletableFuture<OpenAdrSessionSnapshot> registration =
                CompletableFuture.supplyAsync(
                        () -> coordinator.reregister(oldSession)
                );

        assertTrue(operationStarted.await(2, TimeUnit.SECONDS));
        assertEquals(
                OpenAdrSessionState.REREGISTERING,
                coordinator.state()
        );
        assertThrows(
                OpenAdrSessionUnavailableException.class,
                coordinator::requireRegisteredSession
        );

        allowCompletion.countDown();
        assertEquals(newSession, registration.get(2, TimeUnit.SECONDS));
    }

    @Test
    void failedReregistrationLeavesSessionFailedAndPollingStopped() {
        OpenAdrSessionSnapshot oldSession = registeredSession(7, "REG-7");
        RuntimeException failure = new RuntimeException("VTN failure");

        when(sessionProvider.current()).thenReturn(oldSession);
        when(registrationService.performReregistration(oldSession))
                .thenThrow(failure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> coordinator.reregister(oldSession)
        );

        assertEquals(failure, thrown);
        assertEquals(OpenAdrSessionState.FAILED, coordinator.state());
        assertThrows(
                OpenAdrSessionUnavailableException.class,
                coordinator::requireRegisteredSession
        );
        verify(eventPoller, times(2)).stop();
    }

    @Test
    void oldGenerationCannotCancelNewRegistration() {
        OpenAdrSessionSnapshot oldSession = registeredSession(7, "REG-7");
        OpenAdrSessionSnapshot newSession = registeredSession(8, "REG-8");

        when(sessionProvider.current()).thenReturn(oldSession);
        when(registrationService.performReregistration(oldSession))
                .thenReturn(newSession);

        coordinator.reregister(oldSession);

        assertFalse(coordinator.isCurrent(oldSession));
        assertTrue(coordinator.isCurrent(newSession));
        AtomicBoolean staleActionExecuted = new AtomicBoolean();
        assertTrue(
                coordinator.executeIfActive(
                        oldSession,
                        () -> staleActionExecuted.compareAndSet(false, true)
                ).isEmpty()
        );
        assertFalse(staleActionExecuted.get());
        assertThrows(
                StaleOpenAdrSessionException.class,
                () -> coordinator.cancel(oldSession)
        );
    }

    @Test
    void successfulCancellationMovesToCancelledState() {
        OpenAdrSessionSnapshot session = registeredSession(7, "REG-7");
        OpenAdrSessionSnapshot bootstrap = bootstrapSession(8);

        when(sessionProvider.current()).thenReturn(session);
        when(sessionProvider.bootstrap()).thenReturn(bootstrap);

        coordinator.cancel(session);

        assertEquals(OpenAdrSessionState.CANCELLED, coordinator.state());
        assertEquals(bootstrap, coordinator.currentSession());
        verify(registrationService).performCancelRegistration(session);
        verify(eventPoller).stop();
    }

    private OpenAdrSessionSnapshot registeredSession(
            long generation,
            String registrationId
    ) {
        return new OpenAdrSessionSnapshot(
                1L,
                generation,
                "VEN",
                "VTN",
                registrationId,
                Duration.ofSeconds(10)
        );
    }

    private OpenAdrSessionSnapshot bootstrapSession(long generation) {
        return new OpenAdrSessionSnapshot(
                null,
                generation,
                "VEN",
                "VTN",
                null,
                Duration.ofSeconds(10)
        );
    }
}
