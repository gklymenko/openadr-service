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
import com.qcharge.openadr.service.report.ReportRequestHandler;
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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

import static com.qcharge.openadr.LogMessage.PULLED_CANCEL_PARTY_REGISTRATION;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPoller {

    private final OpenAdrProperties properties;
    private final VtnTransportService transportService;
    private final EventProtocolAdapter eventProtocolAdapter;
    private final ReportRequestHandler reportRequestHandler;
    private final TaskScheduler openAdrTaskScheduler;
    private final OpenAdrApplicationErrorMapper applicationErrorMapper;
    private final OpenAdrReplyFactory replyFactory;
    private final OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    private final RegistrationMessageHandler registrationMessageHandler;

    private final ReentrantLock pollLock = new ReentrantLock();

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile boolean running;
    private volatile Duration pollInterval;

    public void start(Duration initialPollInterval) {
        pollInterval = initialPollInterval;
        running = true;

        cancelCurrentTask();
        scheduleNextPoll(Duration.ZERO);

        /*
         * When registration restart is triggered by a payload received inside
         * the current poll cycle, its finally block will schedule the next poll.
         */
        if (pollLock.isHeldByCurrentThread()) {
            log.info(
                    "OpenADR polling interval restored inside active poll cycle. interval={}",
                    pollInterval
            );
            return;
        }

        log.info("OpenADR polling started. interval={}", pollInterval);
    }

    public void stop() {
        running = false;
        cancelCurrentTask();

        log.info("OpenADR polling stopped");
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
        if (!running) {
            return;
        }

        if (!pollLock.tryLock()) {
            log.warn("Skipping OpenADR poll cycle because previous cycle is still running");
            scheduleNextPoll(delayWithJitter());
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
            log.error("OpenADR poll cycle failed", e);
        } finally {
            pollLock.unlock();

            if (running) {
                scheduleNextPoll(delayWithJitter());
            }
        }
    }

    void handlePollingApplicationError(
            OpenAdrApplicationException applicationError,
            OpenAdrSessionSnapshot failedSession
    ) {
        if (applicationError.getAction()
                != ApplicationErrorAction.REQUIRE_REREGISTRATION) {
            log.error(
                    "OpenADR poll operation failed. operation={}, "
                            + "responseCode={}, requestId={}, action={}",
                    applicationError.getOperationName(),
                    applicationError.getResponseCode(),
                    applicationError.getRequestId(),
                    applicationError.getAction(),
                    applicationError
            );
            return;
        }

        log.warn(
                "VTN requires VEN re-registration. operation={}, "
                        + "responseCode={}, requestId={}",
                applicationError.getOperationName(),
                applicationError.getResponseCode(),
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

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            PollExchange exchange = sendPoll(session);
            PollResult result = handlePollResponse(exchange);

            if (result == PollResult.QUEUE_EMPTY) {
                log.debug("VTN queue is empty after {} poll(s)", iteration);
                return;
            }

            if (result == PollResult.STOP) {
                return;
            }
        }

        log.warn("Stopped polling after reaching max queue drain limit: {}", maxIterations);
    }

    private PollExchange sendPoll(OpenAdrSessionSnapshot session) {
        OadrPollType pollPayload = Oadr20bPollBuilders
                .newOadr20bPollBuilder(session.venId())
                .withSchemaVersion(properties.getVen().getProfile())
                .build();

        log.debug("Sending oadrPoll. venId={}", session.venId());

        return new PollExchange(
                session,
                transportService.send(OpenAdrOperations.POLL, pollPayload, session)
        );
    }

    private PollResult handlePollResponse(PollExchange exchange) {
        return lifecycleCoordinator.executeIfActive(
                exchange.session(),
                () -> handleActivePollResponse(exchange)
        ).orElseGet(() -> {
            log.info(
                    "Ignoring poll response from inactive OpenADR session. "
                            + "generation={}",
                    exchange.session().generation()
            );
            return PollResult.STOP;
        });
    }

    private PollResult handleActivePollResponse(PollExchange exchange) {
        Object response = exchange.response();
        if (response == null) {
            log.warn("VTN returned empty response to oadrPoll");
            return PollResult.STOP;
        }

        try {
            return dispatchPollResponse(exchange.session(), response);
        } catch (RuntimeException failure) {
            return handleApplicationFailure(exchange.session(), response, failure);
        }
    }

    private PollResult dispatchPollResponse(
            OpenAdrSessionSnapshot session,
            Object response
    ) {
        return switch (response) {
            case OadrResponseType oadrResponse -> handleOadrResponse(oadrResponse);

            case OadrDistributeEventType distributeEvent -> {
                log.info("Received oadrDistributeEvent. events={}", distributeEvent.getOadrEvent().size());
                eventProtocolAdapter.receive(distributeEvent, session);
                yield PollResult.CONTINUE;
            }

            case OadrCreateReportType createReport -> {
                log.info("Received oadrCreateReport. requests={}", createReport.getOadrReportRequest().size());
                reportRequestHandler.handle(createReport, session);
                yield PollResult.CONTINUE;
            }

            case OadrRegisterReportType registerReport -> {
                log.info("Received oadrRegisterReport. reports={}", registerReport.getOadrReport().size());
                reportRequestHandler.handleRegisterReport(registerReport, session);
                yield PollResult.CONTINUE;
            }

            case OadrCancelReportType cancelReport -> {
                log.info("Received oadrCancelReport");
                reportRequestHandler.handleCancelReport(cancelReport, session);
                yield PollResult.CONTINUE;
            }

            case OadrUpdateReportType updateReport -> {
                log.info("Received oadrUpdateReport. reports={}", updateReport.getOadrReport().size());
                reportRequestHandler.handleUpdateReport(updateReport, session);
                yield PollResult.CONTINUE;
            }

            case OadrCancelPartyRegistrationType cancelRegistration -> {
                log.warn(PULLED_CANCEL_PARTY_REGISTRATION, cancelRegistration.getRegistrationID());

                RemoteCancellationDecision decision =
                        registrationMessageHandler.handleCancelPartyRegistration(cancelRegistration, session);

                yield decision == RemoteCancellationDecision.REJECTED_INVALID_ID
                        ? PollResult.CONTINUE
                        : PollResult.STOP;
            }

            case OadrRequestReregistrationType requestReregistration -> {
                log.warn("Received oadrRequestReregistration. venId={}", requestReregistration.getVenID());

                registrationMessageHandler.handleRequestReregistration(requestReregistration, session);

                yield PollResult.STOP;
            }

            default -> {
                log.warn("Unsupported oadrPoll response type: {}", response.getClass().getName());
                yield PollResult.STOP;
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

    private record PollExchange(
            OpenAdrSessionSnapshot session,
            Object response
    ) {
    }

    private PollResult handleOadrResponse(OadrResponseType response) {
        String code = response.getEiResponse().getResponseCode();

        if ("200".equals(code)) {
            log.debug("oadrPoll returned oadrResponse 200. Queue is empty.");
            return PollResult.QUEUE_EMPTY;
        }

        log.warn(
                "oadrPoll returned non-200 oadrResponse. code={}, description={}",
                code,
                response.getEiResponse().getResponseDescription()
        );

        return PollResult.QUEUE_EMPTY;
    }

    private void scheduleNextPoll(Duration delay) {
        if (!running) {
            return;
        }

        scheduledTask = openAdrTaskScheduler.schedule(
                this::runPollCycle,
                Instant.now().plus(delay)
        );
    }

    private Duration delayWithJitter() {
        Duration jitter = maxJitter();

        if (jitter.isZero() || jitter.isNegative()) {
            return pollInterval;
        }

        long jitterMillis = ThreadLocalRandom.current()
                .nextLong(0, jitter.toMillis() + 1);

        return pollInterval.plusMillis(jitterMillis);
    }

    private void cancelCurrentTask() {
        ScheduledFuture<?> task = scheduledTask;

        if (task != null) {
            task.cancel(false);
            scheduledTask = null;
        }
    }

    private enum PollResult {
        CONTINUE,
        QUEUE_EMPTY,
        STOP
    }

    private int maxQueueDrainPolls() {
        return properties.getTransport().getMaxPollDrainIterations();
    }

    private Duration maxJitter() {
        return Duration.ofSeconds(properties.getTransport().getMaxPollJitterSeconds());
    }
}
