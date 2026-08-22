package com.qcharge.openadr.service.session;

import com.qcharge.openadr.exceptions.OpenAdrSessionUnavailableException;
import com.qcharge.openadr.exceptions.StaleOpenAdrSessionException;
import com.qcharge.openadr.service.registration.RegistrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static com.qcharge.openadr.LogMessage.DUPLICATE_REGISTRATION_REQUEST;
import static com.qcharge.openadr.LogMessage.REREGISTRATION_FOR_STALE_REGISTRATION_SESSION;
import static com.qcharge.openadr.LogMessage.SESSION_STATE_CHANGED_TO;

@Slf4j
@Component
public class OpenAdrSessionLifecycleCoordinator {

    private final OpenAdrSessionProvider sessionProvider;
    private final RegistrationService registrationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private volatile OpenAdrSessionState state;
    private volatile OpenAdrSessionSnapshot currentSession;

    private enum RegistrationFlow { BOOTSTRAP, REGISTER_OR_REREGISTER, FORCED_NEW }

    public OpenAdrSessionLifecycleCoordinator(
            OpenAdrSessionProvider sessionProvider,
            RegistrationService registrationService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.sessionProvider = sessionProvider;
        this.registrationService = registrationService;
        this.eventPublisher = eventPublisher;
    }

    public void bootstrap() {
        executeRegistration(RegistrationFlow.BOOTSTRAP, null);
    }

    public OpenAdrSessionSnapshot register() {
        return executeRegistration(RegistrationFlow.REGISTER_OR_REREGISTER, snapshot());
    }

    public OpenAdrSessionSnapshot forceNewRegistration() {
       return executeRegistration(RegistrationFlow.FORCED_NEW, snapshot());
    }

    public OpenAdrSessionSnapshot reregister(
            OpenAdrSessionSnapshot failedSession
    ) {
        Objects.requireNonNull(failedSession, "failedSession");
        return executeRegistration(RegistrationFlow.REGISTER_OR_REREGISTER, failedSession);
    }

    public void cancel(OpenAdrSessionSnapshot session) {
        Objects.requireNonNull(session, "session");

        lifecycleLock.lock();
        try {
            requireCurrent(session);
            requireRegisteredState();
            transitionTo(OpenAdrSessionState.CANCELLING);
            stopPolling();

            try {
                registrationService.performCancelRegistration(session);
                currentSession = sessionProvider.bootstrap();
                transitionTo(OpenAdrSessionState.CANCELLED);
            } catch (RuntimeException failure) {
                transitionTo(OpenAdrSessionState.FAILED);
                throw failure;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void acceptRemoteCancellation(OpenAdrSessionSnapshot session) {
        Objects.requireNonNull(session, "session");

        lifecycleLock.lock();
        try {
            requireCurrent(session);
            requireRegisteredState();
            transitionTo(OpenAdrSessionState.CANCELLING);
            stopPolling();

            try {
                registrationService.performRemoteCancellation(session);
                currentSession = sessionProvider.bootstrap();
                transitionTo(OpenAdrSessionState.CANCELLED);
            } catch (RuntimeException failure) {
                transitionTo(OpenAdrSessionState.FAILED);
                throw failure;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public OpenAdrSessionSnapshot requireRegisteredSession() {
        OpenAdrSessionSnapshot session = snapshot();

        if (state != OpenAdrSessionState.REGISTERED || !session.registered()) {
            throw new OpenAdrSessionUnavailableException(state);
        }

        return session;
    }

    public OpenAdrSessionSnapshot currentSession() {
        return snapshot();
    }

    public boolean isCurrent(OpenAdrSessionSnapshot session) {
        if (session == null) {
            return false;
        }

        OpenAdrSessionSnapshot current = snapshot();

        return current.generation() == session.generation()
                && Objects.equals(current.registrationEntityId(), session.registrationEntityId())
                && Objects.equals(current.registrationId(), session.registrationId());
    }

    public boolean isActive(OpenAdrSessionSnapshot session) {
        lifecycleLock.lock();
        try {
            return state() == OpenAdrSessionState.REGISTERED
                    && isCurrent(session);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public <T> Optional<T> executeIfActive(
            OpenAdrSessionSnapshot session,
            Supplier<T> action
    ) {
        Objects.requireNonNull(action, "action");

        lifecycleLock.lock();
        try {
            if (state() != OpenAdrSessionState.REGISTERED
                    || !isCurrent(session)) {
                return Optional.empty();
            }

            return Optional.ofNullable(action.get());
        } finally {
            lifecycleLock.unlock();
        }
    }

    public OpenAdrSessionState state() {
        snapshot();
        return state;
    }

    private OpenAdrSessionSnapshot executeRegistration(
            RegistrationFlow flow, @Nullable OpenAdrSessionSnapshot requestedSession
    ) {
        long observedGeneration = requestedSession == null
                ? snapshot().generation()
                : requestedSession.generation();

        lifecycleLock.lock();
        try {
            OpenAdrSessionSnapshot before = snapshot();

            if (before.generation() != observedGeneration) {
                log.info(DUPLICATE_REGISTRATION_REQUEST, observedGeneration, before.generation());
                return before;
            }

            if (requestedSession != null && !isCurrent(requestedSession)) {
                log.info(REREGISTRATION_FOR_STALE_REGISTRATION_SESSION, requestedSession.generation(), before.generation());
                return before;
            }

            OpenAdrSessionState targetState = before.registered()
                    ? OpenAdrSessionState.REREGISTERING
                    : OpenAdrSessionState.REGISTERING;

            transitionTo(targetState);
            stopPolling();

            try {
                currentSession = switch (flow) {
                    case BOOTSTRAP -> registrationService.performBootstrapRegistration();
                    case FORCED_NEW -> registrationService.performForcedNewRegistration();
                    case REGISTER_OR_REREGISTER -> {
                        if (requestedSession != null && requestedSession.registered()) {
                            yield registrationService.performReregistration(requestedSession);
                        }

                        yield registrationService.performRegistration();
                    }
                };

                transitionTo(OpenAdrSessionState.REGISTERED);
                startPolling(currentSession.pollFrequency());
                return currentSession;
            } catch (RuntimeException failure) {
                transitionTo(OpenAdrSessionState.FAILED);
                stopPolling();
                throw failure;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private OpenAdrSessionSnapshot snapshot() {
        OpenAdrSessionSnapshot session = currentSession;

        if (session != null) {
            return session;
        }

        synchronized (this) {
            if (currentSession == null) {
                OpenAdrSessionSnapshot initialized = sessionProvider.current();
                state = initialized.registered()
                        ? OpenAdrSessionState.REGISTERED
                        : OpenAdrSessionState.UNREGISTERED;
                currentSession = initialized;
            }
            return currentSession;
        }
    }

    private void requireCurrent(OpenAdrSessionSnapshot session) {
        if (!isCurrent(session)) {
            throw new StaleOpenAdrSessionException(session.generation());
        }
    }

    private void requireRegisteredState() {
        if (state != OpenAdrSessionState.REGISTERED) {
            throw new OpenAdrSessionUnavailableException(state);
        }
    }

    private void transitionTo(OpenAdrSessionState nextState) {
        OpenAdrSessionState previous = state;
        state = nextState;
        log.info(SESSION_STATE_CHANGED_TO, previous, nextState,
                currentSession == null ? null : currentSession.generation()
        );
    }

    private void startPolling(Duration pollFrequency) {
        eventPublisher.publishEvent(new OpenAdrPollingStartedEvent(pollFrequency));
    }

    private void stopPolling() {
        eventPublisher.publishEvent(new OpenAdrPollingStoppedEvent());
    }
}
