package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
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
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventPoller {

    private final OpenAdrProperties properties;
    private final VtnTransportService transportService;
    private final DrEventHandler drEventHandler;
    private final ReportRequestHandler reportRequestHandler;
    private final TaskScheduler openAdrTaskScheduler;

    /**
     * Lazy provider avoids direct circular dependency:
     * RegistrationService -> EventPoller
     * EventPoller -> RegistrationService
     */
    private final ObjectProvider<RegistrationService> registrationServiceProvider;

    private final ReentrantLock pollLock = new ReentrantLock();

    private volatile ScheduledFuture<?> scheduledTask;
    private volatile boolean running;
    private volatile Duration pollInterval;

    public void start(Duration initialPollInterval) {
        pollInterval = sanitizePollInterval(initialPollInterval);
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

    public void updatePollInterval(Duration newPollInterval) {
        Duration sanitized = sanitizePollInterval(newPollInterval);

        if (sanitized.equals(pollInterval)) {
            return;
        }

        log.info("Updating OpenADR poll interval from {} to {}", pollInterval, sanitized);
        pollInterval = sanitized;

        if (running) {
            cancelCurrentTask();
            scheduleNextPoll(delayWithJitter());
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

        try {
            pollUntilQueueEmpty();
        } catch (Exception e) {
            log.error("OpenADR poll cycle failed", e);
        } finally {
            pollLock.unlock();

            if (running) {
                scheduleNextPoll(delayWithJitter());
            }
        }
    }

    /**
     * Pull mode rule: if VTN has queued payloads, it returns one payload per oadrPoll.
     * VEN should continue polling until VTN returns oadrResponse.
     */
    private void pollUntilQueueEmpty() {
        int maxIterations = maxQueueDrainPolls();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            Object response = sendPoll();
            PollResult result = handlePollResponse(response);

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

    private Object sendPoll() {
        String venId = registrationServiceProvider.getObject().currentVenId();

        OadrPollType pollPayload = Oadr20bPollBuilders
                .newOadr20bPollBuilder(venId)
                .withSchemaVersion(properties.getVen().getProfile())
                .build();

        log.debug("Sending oadrPoll. venId={}", venId);

        return transportService.poll(pollPayload);
    }

    private PollResult handlePollResponse(Object response) {
        if (response == null) {
            log.warn("VTN returned empty response to oadrPoll");
            return PollResult.STOP;
        }

        return switch (response) {
            case OadrResponseType oadrResponse -> handleOadrResponse(oadrResponse);

            case OadrDistributeEventType distributeEvent -> {
                log.info("Received oadrDistributeEvent. events={}", distributeEvent.getOadrEvent().size());
                drEventHandler.handle(distributeEvent);
                yield PollResult.CONTINUE;
            }

            case OadrCreateReportType createReport -> {
                log.info("Received oadrCreateReport. requests={}", createReport.getOadrReportRequest().size());
                reportRequestHandler.handle(createReport);
                yield PollResult.CONTINUE;
            }

            case OadrRegisterReportType registerReport -> {
                log.info("Received oadrRegisterReport. reports={}", registerReport.getOadrReport().size());
                reportRequestHandler.handleRegisterReport(registerReport);
                yield PollResult.CONTINUE;
            }

            case OadrCancelReportType cancelReport -> {
                log.info("Received oadrCancelReport");
                reportRequestHandler.handleCancelReport(cancelReport);
                yield PollResult.CONTINUE;
            }

            case OadrUpdateReportType updateReport -> {
                log.info("Received oadrUpdateReport. reports={}", updateReport.getOadrReport().size());
                reportRequestHandler.handleUpdateReport(updateReport);
                yield PollResult.CONTINUE;
            }

            case OadrCancelPartyRegistrationType cancelRegistration -> {
                log.warn("Received oadrCancelPartyRegistration. registrationId={}",
                        cancelRegistration.getRegistrationID());

                registrationServiceProvider
                        .getObject()
                        .handleCancelPartyRegistration(cancelRegistration);

                yield PollResult.STOP;
            }

            case OadrRequestReregistrationType requestReregistration -> {
                log.warn("Received oadrRequestReregistration. venId={}", requestReregistration.getVenID());

                registrationServiceProvider.getObject()
                        .handleRequestReregistration(requestReregistration);

                yield PollResult.STOP;
            }

            default -> {
                log.warn("Unsupported oadrPoll response type: {}", response.getClass().getName());
                yield PollResult.STOP;
            }
        };
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

    private Duration sanitizePollInterval(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return Duration.ofSeconds(properties.getTransport().getPollIntervalSeconds());
        }

        return requested;
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