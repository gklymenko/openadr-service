package com.qcharge.openadr.service.session;

import com.qcharge.openadr.exceptions.OpenAdrSessionUnavailableException;
import com.qcharge.openadr.exceptions.StaleOpenAdrSessionException;
import com.qcharge.openadr.service.registration.RegistrationService;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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

    private enum RegistrationFlow { REGISTER, REREGISTER, FORCED_NEW }
    private enum CancellationFlow { VEN_INITIATED, VTN_INITIATED }

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
        OpenAdrSessionSnapshot session = snapshot();

        RegistrationFlow flow = session.registered()
                ? RegistrationFlow.REREGISTER
                : RegistrationFlow.REGISTER;

        executeRegistration(flow, session);
    }

    public OpenAdrSessionSnapshot register() {
        return executeRegistration(RegistrationFlow.REGISTER, snapshot());
    }

    public OpenAdrSessionSnapshot forceNewRegistration() {
        return executeRegistration(RegistrationFlow.FORCED_NEW, snapshot());
    }

    public OpenAdrSessionSnapshot reregister(@NonNull OpenAdrSessionSnapshot failedSession) {
        return executeRegistration(RegistrationFlow.REREGISTER, failedSession);
    }

    public void cancel(@NonNull OpenAdrSessionSnapshot session) {
        executeCancellation(session, CancellationFlow.VEN_INITIATED);
    }

    public void acceptRemoteCancellation(@NonNull OpenAdrSessionSnapshot session) {
        executeCancellation(session, CancellationFlow.VTN_INITIATED);
    }

    private void executeCancellation(
            OpenAdrSessionSnapshot session, CancellationFlow flow
    ) {
        lifecycleLock.lock();
        try {
            requireCurrent(session);
            requireRegisteredState();
            transitionTo(OpenAdrSessionState.CANCELLING);
            stopPolling();

            try {
                performCancellation(flow, session);
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

    private void performCancellation(
            CancellationFlow flow,
            OpenAdrSessionSnapshot session
    ) {
        switch (flow) {
            case VEN_INITIATED ->
                    registrationService.performCancelRegistration(session);
            case VTN_INITIATED ->
                    registrationService.completeCancellation(session);
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
            RegistrationFlow flow, OpenAdrSessionSnapshot requestedSession
    ) {
        long observedGeneration = requestedSession.generation();

        lifecycleLock.lock();
        try {
            OpenAdrSessionSnapshot before = snapshot();

            if (before.generation() != observedGeneration) {
                log.info(DUPLICATE_REGISTRATION_REQUEST, observedGeneration, before.generation());
                return before;
            }

            if (!isCurrent(requestedSession)) {
                log.info(REREGISTRATION_FOR_STALE_REGISTRATION_SESSION, requestedSession.generation(), before.generation());
                return before;
            }

            requireValidFlow(flow, requestedSession);

            OpenAdrSessionState targetState = before.registered()
                    ? OpenAdrSessionState.REREGISTERING
                    : OpenAdrSessionState.REGISTERING;

            transitionTo(targetState);
            stopPolling();

            try {
                currentSession = switch (flow) {
                    case REGISTER -> registrationService.performRegistration();
                    case REREGISTER -> registrationService.performReregistration(requestedSession);
                    case FORCED_NEW -> registrationService.performForcedNewRegistration();
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

    private void requireValidFlow(
            RegistrationFlow flow, OpenAdrSessionSnapshot session
    ) {
        if (flow == RegistrationFlow.REGISTER && session.registered()) {
            throw new IllegalStateException(
                    "REGISTER flow requires an unregistered session"
            );
        }

        if (flow == RegistrationFlow.REREGISTER && !session.registered()) {
            throw new IllegalStateException(
                    "REREGISTER flow requires a registered session"
            );
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
