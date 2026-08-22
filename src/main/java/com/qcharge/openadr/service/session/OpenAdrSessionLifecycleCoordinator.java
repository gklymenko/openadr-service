package com.qcharge.openadr.service.session;

import com.qcharge.openadr.exceptions.OpenAdrSessionUnavailableException;
import com.qcharge.openadr.exceptions.StaleOpenAdrSessionException;
import com.qcharge.openadr.service.event.EventPoller;
import com.qcharge.openadr.service.registration.RegistrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

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
    private final ObjectProvider<RegistrationService> registrationServiceProvider;
    private final ObjectProvider<EventPoller> eventPollerProvider;
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private volatile OpenAdrSessionState state;
    private volatile OpenAdrSessionSnapshot currentSession;

    public OpenAdrSessionLifecycleCoordinator(
            OpenAdrSessionProvider sessionProvider,
            ObjectProvider<RegistrationService> registrationServiceProvider,
            ObjectProvider<EventPoller> eventPollerProvider
    ) {
        this.sessionProvider = sessionProvider;
        this.registrationServiceProvider = registrationServiceProvider;
        this.eventPollerProvider = eventPollerProvider;
    }

    public void bootstrap() {
        executeRegistration(true, null, false);
    }

    public OpenAdrSessionSnapshot register() {
        return executeRegistration(false, snapshot(), false);
    }

    public OpenAdrSessionSnapshot forceNewRegistration() {
        return executeRegistration(false, snapshot(), true);
    }

    public OpenAdrSessionSnapshot reregister(
            OpenAdrSessionSnapshot failedSession
    ) {
        Objects.requireNonNull(failedSession, "failedSession");
        return executeRegistration(false, failedSession, false);
    }

    public void cancel(OpenAdrSessionSnapshot session) {
        Objects.requireNonNull(session, "session");

        lifecycleLock.lock();
        try {
            requireCurrent(session);
            requireState(OpenAdrSessionState.REGISTERED);
            transitionTo(OpenAdrSessionState.CANCELLING);
            eventPollerProvider.getObject().stop();

            try {
                registrationServiceProvider
                        .getObject()
                        .performCancelRegistration(session);
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
            requireState(OpenAdrSessionState.REGISTERED);
            transitionTo(OpenAdrSessionState.CANCELLING);
            eventPollerProvider.getObject().stop();

            try {
                registrationServiceProvider
                        .getObject()
                        .performRemoteCancellation(session);
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
            boolean startup, @Nullable OpenAdrSessionSnapshot requestedSession, boolean forcedNewRegistration
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
            eventPollerProvider.getObject().stop();

            try {
                RegistrationService registrationService = registrationServiceProvider.getObject();

                OpenAdrSessionSnapshot registered = forcedNewRegistration
                        ? registrationService.performForcedNewRegistration()
                        : startup
                        ? registrationService.performBootstrapRegistration()
                        : requestedSession != null && requestedSession.registered()
                        ? registrationService.performReregistration(requestedSession)
                        : registrationService.performRegistration();

                currentSession = registered;
                transitionTo(OpenAdrSessionState.REGISTERED);
                eventPollerProvider.getObject().start(
                        registered.pollFrequency()
                );
                return registered;
            } catch (RuntimeException failure) {
                transitionTo(OpenAdrSessionState.FAILED);
                eventPollerProvider.getObject().stop();
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

    private void requireState(OpenAdrSessionState expected) {
        if (state != expected) {
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
}
