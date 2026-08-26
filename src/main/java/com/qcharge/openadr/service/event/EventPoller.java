package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bPollBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.registration.RemoteCancellationDecision;
import com.qcharge.openadr.service.registration.RegistrationMessageHandler;
import com.qcharge.openadr.service.report.PulledReportCommand;
import com.qcharge.openadr.service.report.ReportCommandQueue;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.ApplicationErrorAction;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import com.qcharge.openadr.service.transport.OpenAdrReply;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import static com.qcharge.openadr.LogMessage.POLLING_STARTED;
import static com.qcharge.openadr.LogMessage.POLLING_STOPPED;
import static com.qcharge.openadr.LogMessage.POLLING_STOPPED_ON_MAX_LIMIT;
import static com.qcharge.openadr.LogMessage.POLL_CYCLE_FAILED;
import static com.qcharge.openadr.LogMessage.POLL_CYCLE_FAILED_BY_VTN_RESPONSE;
import static com.qcharge.openadr.LogMessage.PULLED_CANCEL_PARTY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.PULLED_REQUEST_RE_REGISTRATION;
import static com.qcharge.openadr.LogMessage.PULLED_UNSUPPORTED_TYPE;
import static com.qcharge.openadr.LogMessage.SENDING_OADR_POLL;
import static com.qcharge.openadr.LogMessage.VTN_QUEUE_EMPTY;
import static com.qcharge.openadr.LogMessage.VTN_REQUIRES_VEN_RE_REGISTRATION;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPoller {

    private final OpenAdrProperties properties;
    private final VtnTransportService transportService;
    private final EventProtocolAdapter eventProtocolAdapter;
    private final ReportCommandQueue reportCommandQueue;
    private final TaskScheduler openAdrTaskScheduler;
    private final OpenAdrApplicationErrorMapper applicationErrorMapper;
    private final OpenAdrReplyFactory replyFactory;
    private final OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    private final RegistrationMessageHandler registrationMessageHandler;

    private final ReentrantLock pollLock = new ReentrantLock();
    private final Object schedulingMonitor = new Object();
    private ScheduledFuture<?> scheduledTask;

    public void start(Duration initialPollInterval) {
        Duration pollInterval = requirePositivePollInterval(initialPollInterval);

        synchronized (schedulingMonitor) {
            cancelCurrentTask();
            scheduledTask = Objects.requireNonNull(
                    openAdrTaskScheduler.schedule(
                            this::runPollCycle, pollingTrigger(pollInterval)
                    ),
                    "OpenADR polling task was not scheduled"
            );
        }

        log.info(POLLING_STARTED, pollInterval);
    }

    public void stop() {
        synchronized (schedulingMonitor) {
            cancelCurrentTask();
        }

        log.info(POLLING_STOPPED);
    }

    /**
     * Runs a manual VEN pull operation without overlapping an active oadrPoll.
     *
     * <p>If a poll cycle is already running, the caller waits until that cycle
     * releases the lock. A scheduled poll that starts while the manual operation
     * owns the lock is skipped and rescheduled by the normal polling flow.</p>
     */
    public void executeExclusivelyWithPolling(Runnable action) {
        pollLock.lock();
        try {
            action.run();
        } finally {
            pollLock.unlock();
        }
    }

    private void runPollCycle() {
        if (!pollLock.tryLock()) {
            log.warn("Skipping OpenADR poll cycle because previous cycle is still running");
            return;
        }

        OpenAdrSessionSnapshot session = null;

        try {
            session = lifecycleCoordinator.requireRegisteredSession();
            pollUntilQueueEmpty(session);
        } catch (OpenAdrApplicationException applicationError) {
            if (session == null) {
                log.error(
                        "OpenADR application error occurred before a registered polling session was captured",
                        applicationError
                );
            } else {
                handlePollingApplicationError(applicationError, session);
            }
        } catch (Exception e) {
            log.error(POLL_CYCLE_FAILED, e);
        } finally {
            pollLock.unlock();
        }
    }

    private void handlePollingApplicationError(
            OpenAdrApplicationException applicationError, OpenAdrSessionSnapshot failedSession
    ) {
        if (applicationError.getAction() != ApplicationErrorAction.REQUIRE_REREGISTRATION) {
            log.error(
                    POLL_CYCLE_FAILED_BY_VTN_RESPONSE,
                    applicationError.getOperationName(), applicationError.getResponseCode(),
                    applicationError.getRequestId(), applicationError.getAction(), applicationError
            );
            return;
        }

        log.warn(
                VTN_REQUIRES_VEN_RE_REGISTRATION,
                applicationError.getOperationName(), applicationError.getResponseCode(),
                applicationError.getRequestId()
        );

        stop();
        lifecycleCoordinator.reregister(failedSession);
    }

    /**
     * Pull mode rule: if VTN has queued payloads, it returns one payload per oadrPoll.
     * VEN should continue polling until VTN returns oadrResponse.
     */
    private void pollUntilQueueEmpty(OpenAdrSessionSnapshot session) {
        int maxIterations = maxQueueDrainPolls();
        List<PulledReportCommand> reportCommands = new ArrayList<>();

        try {
            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                PollExchange exchange = sendPoll(session);
                PollResult result = handlePollResponse(exchange, reportCommands);

                if (result == PollResult.QUEUE_EMPTY) {
                    log.debug(VTN_QUEUE_EMPTY, iteration);
                    return;
                }

                if (result == PollResult.ABORT_CYCLE) {
                    return;
                }
            }

            log.warn(POLLING_STOPPED_ON_MAX_LIMIT, maxIterations);
        } finally {
            reportCommandQueue.enqueueAll(reportCommands);
        }
    }

    private PollExchange sendPoll(OpenAdrSessionSnapshot session) {
        OadrPollType pollPayload = Oadr20bPollBuilders
                .newOadr20bPollBuilder(session.venId())
                .build();

        log.debug(SENDING_OADR_POLL, session.venId());

        return new PollExchange(
                session,
                transportService.send(OpenAdrOperations.POLL, pollPayload, session)
        );
    }

    private PollResult handlePollResponse(
            PollExchange exchange,
            List<PulledReportCommand> reportCommands
    ) {
        return lifecycleCoordinator.executeIfActive(
                exchange.session(), () -> handleActivePollResponse(exchange, reportCommands)
        ).orElseGet(() -> {
            log.info(
                    "Ignoring poll response from inactive OpenADR session. generation={}",
                    exchange.session().generation()
            );
            return PollResult.ABORT_CYCLE;
        });
    }

    private PollResult handleActivePollResponse(
            PollExchange exchange,
            List<PulledReportCommand> reportCommands
    ) {
        try {
            return dispatchPollResponse(exchange.session(), exchange.response(), reportCommands);

        } catch (RuntimeException failure) {
            return handleApplicationFailure(exchange.session(), exchange.response(), failure);
        }
    }

    private PollResult dispatchPollResponse(
            OpenAdrSessionSnapshot session,
            Object response,
            List<PulledReportCommand> reportCommands
    ) {
        return switch (response) {
            case OadrResponseType ignored -> PollResult.QUEUE_EMPTY;

            case OadrDistributeEventType distributeEvent -> {
                log.info("Received oadrDistributeEvent. events={}", distributeEvent.getOadrEvent().size());
                eventProtocolAdapter.receive(distributeEvent, session);
                yield PollResult.CONTINUE;
            }

            case OadrCreateReportType createReport -> {
                log.info("Received oadrCreateReport. requests={}", createReport.getOadrReportRequest().size());
                reportCommands.add(PulledReportCommand.create(createReport, session));
                yield PollResult.CONTINUE;
            }

            case OadrRegisterReportType registerReport -> {
                log.info("Received oadrRegisterReport. reports={}", registerReport.getOadrReport().size());
                reportCommands.add(PulledReportCommand.register(registerReport, session));
                yield PollResult.CONTINUE;
            }

            case OadrCancelReportType cancelReport -> {
                log.info("Received oadrCancelReport");
                reportCommands.add(PulledReportCommand.cancel(cancelReport, session));
                yield PollResult.CONTINUE;
            }

            case OadrUpdateReportType updateReport -> {
                log.info("Received oadrUpdateReport. reports={}", updateReport.getOadrReport().size());
                reportCommands.add(PulledReportCommand.update(updateReport, session));
                yield PollResult.CONTINUE;
            }

            case OadrCancelPartyRegistrationType cancelRegistration -> {
                log.warn(PULLED_CANCEL_PARTY_REGISTRATION, cancelRegistration.getRegistrationID());

                RemoteCancellationDecision decision =
                        registrationMessageHandler.handleCancelPartyRegistration(cancelRegistration, session);

                yield decision == RemoteCancellationDecision.REJECTED_INVALID_ID
                        ? PollResult.CONTINUE
                        : PollResult.ABORT_CYCLE;
            }

            case OadrRequestReregistrationType requestReregistration -> {
                log.warn(PULLED_REQUEST_RE_REGISTRATION, requestReregistration.getVenID());
                registrationMessageHandler.handleRequestReregistration(requestReregistration, session);
                yield PollResult.ABORT_CYCLE;
            }

            default -> {
                log.warn(PULLED_UNSUPPORTED_TYPE, response.getClass().getName());
                yield PollResult.ABORT_CYCLE;
            }
        };
    }

    private PollResult handleApplicationFailure(
            OpenAdrSessionSnapshot session,
            Object inboundPayload,
            RuntimeException failure
    ) {
        OpenAdrApplicationException applicationError =
                applicationErrorMapper.map(failure, inboundPayload);

        OpenAdrReply<?, ?> reply = replyFactory
                .createApplicationErrorReply(
                        inboundPayload,
                        session.venId(),
                        applicationError
                )
                .orElseThrow(() -> applicationError);

        try {
            transportService.sendReply(reply, session);
        } catch (RuntimeException replyFailure) {
            applicationError.addSuppressed(replyFailure);
            throw applicationError;
        }

        log.warn(
                "OpenADR request failed and application error reply was sent. " +
                        "requestType={}, replyOperation={}, responseCode={}, requestId={}",
                inboundPayload.getClass().getSimpleName(),
                reply.operation().name(),
                applicationError.getResponseCode(),
                applicationError.getRequestId()
        );

        return PollResult.CONTINUE;
    }

    private Trigger pollingTrigger(Duration pollInterval) {
        return context -> {
            Instant lastCompletion = context.lastCompletion();

            if (lastCompletion == null) {
                return context.getClock().instant();
            }

            // Schedule relative to completion, not start time. A slow HTTP exchange therefore delays the next poll
            // instead of creating overlapping polls.
            return lastCompletion
                    .plus(pollInterval)
                    .plus(randomJitter());
        };
    }

    private Duration randomJitter() {
        long maxJitterMillis = maxJitter().toMillis();

        if (maxJitterMillis <= 0) {
            return Duration.ZERO;
        }

        return Duration.ofMillis(
                ThreadLocalRandom.current().nextLong(maxJitterMillis + 1)
        );
    }

    private Duration requirePositivePollInterval(Duration pollInterval) {
        if (pollInterval == null
                || pollInterval.isZero()
                || pollInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "OpenADR poll interval must be positive"
            );
        }

        return pollInterval;
    }

    private void cancelCurrentTask() {
        ScheduledFuture<?> task = scheduledTask;

        if (task != null) {
            // Cancel future executions without interrupting an in-flight HTTP exchange.
            // Interrupting transport code may leave the protocol outcome unknown. The
            // completed response is still checked against the active session before use.
            task.cancel(false);
            scheduledTask = null;
        }
    }
    private int maxQueueDrainPolls() {
        return properties.getTransport().getMaxPollDrainIterations();
    }

    private Duration maxJitter() {
        return Duration.ofSeconds(properties.getTransport().getMaxPollJitterSeconds());
    }

    private record PollExchange(
            OpenAdrSessionSnapshot session,
            Object response
    ) {
    }

    private enum PollResult {
        CONTINUE,
        QUEUE_EMPTY,
        ABORT_CYCLE
    }

}
