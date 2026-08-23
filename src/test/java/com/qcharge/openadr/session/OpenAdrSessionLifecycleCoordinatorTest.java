package com.qcharge.openadr.session;

import com.qcharge.openadr.exceptions.OpenAdrSessionUnavailableException;
import com.qcharge.openadr.exceptions.StaleOpenAdrSessionException;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.session.OpenAdrPollingStartedEvent;
import com.qcharge.openadr.service.session.OpenAdrPollingStoppedEvent;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.session.OpenAdrSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
    @Mock RegistrationService registrationService;
    @Mock ApplicationEventPublisher eventPublisher;

    private OpenAdrSessionLifecycleCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new OpenAdrSessionLifecycleCoordinator(
                sessionProvider,
                registrationService,
                eventPublisher
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
        verify(eventPublisher).publishEvent(
                new OpenAdrPollingStartedEvent(newSession.pollFrequency())
        );
    }

    @Test
    void registerUsesRegularRegistrationForUnregisteredSession() {
        OpenAdrSessionSnapshot initialSession = bootstrapSession(1);
        OpenAdrSessionSnapshot registeredSession = registeredSession(2, "REG-2");
        when(sessionProvider.current()).thenReturn(initialSession);
        when(registrationService.performRegistration()).thenReturn(registeredSession);

        assertEquals(registeredSession, coordinator.register());

        verify(registrationService).performRegistration();
    }

    @Test
    void forceNewRegistrationUsesForcedRegistrationFlow() {
        OpenAdrSessionSnapshot currentSession = registeredSession(1, "REG-1");
        OpenAdrSessionSnapshot registeredSession = registeredSession(2, "REG-2");
        when(sessionProvider.current()).thenReturn(currentSession);
        when(registrationService.performForcedNewRegistration()).thenReturn(registeredSession);

        assertEquals(registeredSession, coordinator.forceNewRegistration());

        verify(registrationService).performForcedNewRegistration();
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
        verify(eventPublisher, times(2)).publishEvent(
                new OpenAdrPollingStoppedEvent()
        );
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
        verify(eventPublisher).publishEvent(new OpenAdrPollingStoppedEvent());
    }

    @Test
    void acceptedRemoteCancellationUsesSameLifecycleTransition() {
        OpenAdrSessionSnapshot session = registeredSession(7, "REG-7");
        OpenAdrSessionSnapshot bootstrap = bootstrapSession(8);

        when(sessionProvider.current()).thenReturn(session);
        when(sessionProvider.bootstrap()).thenReturn(bootstrap);

        coordinator.acceptRemoteCancellation(session);

        assertEquals(OpenAdrSessionState.CANCELLED, coordinator.state());
        assertEquals(bootstrap, coordinator.currentSession());
        verify(registrationService).completeCancellation(session);
        verify(eventPublisher).publishEvent(new OpenAdrPollingStoppedEvent());
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
