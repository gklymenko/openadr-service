package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.integration.ocpp.OcppIntegrationService;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.entity.DrEventInterval;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.EventResponses.EventResponse;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.EventValidationService.ParsedSignal;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class DrEventHandler {

    private static final String RESPONSE_REQUIRED_ALWAYS = "always";
    private static final EnumSet<DrEvent.ExecutionStatus> KNOWN_EVENT_STATUSES = EnumSet.of(
            DrEvent.ExecutionStatus.RECEIVED,
            DrEvent.ExecutionStatus.SCHEDULED,
            DrEvent.ExecutionStatus.APPLIED,
            DrEvent.ExecutionStatus.FAILED
    );

    private final OpenAdrProperties properties;
    private final DrEventRepository drEventRepository;
    private final VtnTransportService transportService;
    private final EventOptDecisionService eventOptDecisionService;
    private final EventValidationService eventValidationService;
    private final OcppIntegrationService ocppIntegrationService;
    private final OpenAdrSessionProvider sessionProvider;

    @Transactional
    public void handle(OadrDistributeEventType distributeEvent) {
        handle(distributeEvent, sessionProvider.current());
    }

    @Transactional
    public void handle(
            OadrDistributeEventType distributeEvent,
            OpenAdrSessionSnapshot session
    ) {
        VtnEventLogger.logReceivedEvents(distributeEvent);

        String venId = session.venId();
        String distributeRequestId = safeRequestId(distributeEvent.getRequestID());

        // Rule 42: with eventResponses present, eiResponse.requestID is empty;
        // each eventResponse carries oadrDistributeEvent.requestID instead.
        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder("", ApplicationLayerErrorCodes.OK)
                .build();

        var createdEventBuilder = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, venId);

        int eventResponseCount = 0;
        Set<String> eventIds = new HashSet<>();

        for (OadrEvent oadrEvent : distributeEvent.getOadrEvent()) {
            EventProcessingResult result =
                    processEventSafely(oadrEvent, eventIds, session.venId());

            if (!requiresCreatedEventResponse(oadrEvent)) {
                continue;
            }

            EventResponse eventResponse = Oadr20bEiEventBuilders
                    .newOadr20bCreatedEventEventResponseBuilder(
                            result.eventId(),
                            result.modificationNumber(),
                            distributeRequestId,
                            result.responseCode(),
                            result.optType()
                    )
                    .build();

            createdEventBuilder.addEventResponse(eventResponse);
            eventResponseCount++;
        }

        reconcileImplicitCancellations(eventIds);

        if (eventResponseCount == 0) {
            log.info("No oadrCreatedEvent sent because no event required application response");
            return;
        }

        OadrCreatedEventType createdEvent = createdEventBuilder.build();
        transportService.send(OpenAdrOperations.CREATED_EVENT, createdEvent, session);

        log.info("Sent oadrCreatedEvent. eventResponses={}", eventResponseCount);
    }

    private EventProcessingResult processEventSafely(
            OadrEvent oadrEvent,
            Set<String> eventIds,
            String venId
    ) {
        try {
            return processEvent(oadrEvent, eventIds, venId);
        } catch (EventValidationException e) {
            EventDescriptorType descriptor = descriptorOf(oadrEvent);

            String eventId = descriptor != null && descriptor.getEventID() != null
                    ? descriptor.getEventID()
                    : "unknown";
            long modificationNumber = descriptor != null ? descriptor.getModificationNumber() : 0L;

            log.warn(
                    "OpenADR event validation failed. eventId={}, modificationNumber={}, responseCode={}, reason={}",
                    eventId,
                    modificationNumber,
                    e.getResponseCode(),
                    e.getMessage()
            );

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    e.getResponseCode(),
                    OptTypeType.OPT_OUT
            );
        } catch (TargetMismatchException e) {
            EventDescriptorType descriptor = descriptorOf(oadrEvent);

            String eventId = descriptor != null && descriptor.getEventID() != null
                    ? descriptor.getEventID()
                    : "unknown";
            long modificationNumber = descriptor != null ? descriptor.getModificationNumber() : 0L;

            log.warn(
                    "OpenADR event target mismatch. eventId={}, modificationNumber={}, reason={}",
                    eventId,
                    modificationNumber,
                    e.getMessage()
            );

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.TARGET_MISMATCH,
                    OptTypeType.OPT_OUT
            );
        } catch (IllegalArgumentException e) {
            EventDescriptorType descriptor = descriptorOf(oadrEvent);

            String eventId = descriptor != null && descriptor.getEventID() != null
                    ? descriptor.getEventID()
                    : "unknown";
            long modificationNumber = descriptor != null ? descriptor.getModificationNumber() : 0L;

            log.warn(
                    "Invalid OpenADR event data. eventId={}, modificationNumber={}, reason={}",
                    eventId,
                    modificationNumber,
                    e.getMessage()
            );

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.INVALID_DATA,
                    OptTypeType.OPT_OUT
            );
        } catch (Exception e) {
            EventDescriptorType descriptor = descriptorOf(oadrEvent);

            String eventId = descriptor != null && descriptor.getEventID() != null
                    ? descriptor.getEventID()
                    : "unknown";
            long modificationNumber = descriptor != null ? descriptor.getModificationNumber() : 0L;

            log.error(
                    "Failed to process OpenADR event. eventId={}, modificationNumber={}",
                    eventId,
                    modificationNumber,
                    e
            );

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.INVALID_DATA,
                    OptTypeType.OPT_OUT
            );
        }
    }

    private EventProcessingResult processEvent(
            OadrEvent oadrEvent,
            Set<String> eventIds,
            String venId
    ) {
        EventDescriptorType descriptor = requireDescriptor(oadrEvent);

        String eventId = requireEventId(descriptor);
        long modificationNumber = descriptor.getModificationNumber();

        if (!eventIds.add(eventId)) {
            throw new EventValidationException(
                    "eventID must be unique within oadrDistributeEvent: " + eventId,
                    ApplicationLayerErrorCodes.INVALID_ID
            );
        }

        log.info(
                "Processing OpenADR event. eventId={}, status={}, modificationNumber={}",
                eventId, descriptor.getEventStatus(), modificationNumber
        );

        DrEvent existingEvent = drEventRepository.findByEventId(eventId).orElse(null);

        boolean unknownCancellation = existingEvent == null && isCancelled(descriptor);
        EventVersionState versionState = unknownCancellation
                ? EventVersionState.NEW
                : validateModificationSequence(existingEvent, modificationNumber);

        if (versionState == EventVersionState.OUT_OF_SEQUENCE) {
            log.warn(
                    "Out-of-sequence OpenADR event. eventId={}, currentModificationNumber={}, receivedModificationNumber={}",
                    eventId,
                    existingEvent != null ? existingEvent.getModificationNumber() : null,
                    modificationNumber
            );

            return new EventProcessingResult(
                    eventId, modificationNumber,
                    ApplicationLayerErrorCodes.OUT_OF_SEQUENCE, OptTypeType.OPT_OUT
            );
        }

        eventValidationService.validateTargetAndMarketContext(oadrEvent, venId);

        if (isCancelled(descriptor)) {
            if (unknownCancellation) {
                log.info("Ignoring cancellation for unknown OpenADR event. eventId={}", eventId);
                return new EventProcessingResult(
                        eventId,
                        modificationNumber,
                        ApplicationLayerErrorCodes.OK,
                        OptTypeType.OPT_IN
                );
            }
            if (versionState == EventVersionState.DUPLICATE) {
                return successfulDuplicate(existingEvent, eventId, modificationNumber);
            }
            saveCancelledEvent(oadrEvent, existingEvent);

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.OK,
                    OptTypeType.OPT_IN
            );
        }

        if (isCompleted(descriptor)) {
            if (versionState == EventVersionState.DUPLICATE) {
                if (existingEvent.getVtnStatus() != DrEvent.EventStatus.COMPLETED) {
                    existingEvent.setVtnStatus(DrEvent.EventStatus.COMPLETED);
                    drEventRepository.save(existingEvent);
                }
                return successfulDuplicate(existingEvent, eventId, modificationNumber);
            }
            saveCompletedEvent(oadrEvent, existingEvent);

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.OK,
                    existingEvent != null && existingEvent.getOptType() == DrEvent.OptType.OPT_IN
                            ? OptTypeType.OPT_IN
                            : OptTypeType.OPT_OUT
            );
        }

        List<ParsedSignal> parsedSignals = eventValidationService.parseSignals(oadrEvent);
        Optional<ParsedSignal> parsedSignal = eventValidationService.selectPreferredSignal(parsedSignals);

        if (parsedSignal.isEmpty()) {
            log.warn("Unsupported event signal. eventId={} -> responseCode={}", eventId,
                    ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED);

            return new EventProcessingResult(
                    eventId,
                    modificationNumber,
                    ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED,
                    OptTypeType.OPT_OUT
            );
        }

        ParsedSignal signal = parsedSignal.get();
        OptTypeType optType = eventOptDecisionService.determineOptType(oadrEvent, signal);

        if (versionState == EventVersionState.DUPLICATE) {
            return successfulDuplicate(existingEvent, eventId, modificationNumber);
        }

        saveOrUpdateEvent(oadrEvent, existingEvent, optType, parsedSignals, signal.signalId());

        log.info(
                "OpenADR event persisted for lifecycle execution. eventId={}, optType={}",
                eventId,
                optType
        );

        return new EventProcessingResult(
                eventId,
                modificationNumber,
                ApplicationLayerErrorCodes.OK,
                optType
        );
    }

    private EventVersionState validateModificationSequence(
            DrEvent existingEvent,
            long receivedModificationNumber
    ) {
        if (existingEvent == null)
            return EventVersionState.NEW;

        int storedModificationNumber = existingEvent.getModificationNumber();
        if (receivedModificationNumber == storedModificationNumber) {
            return EventVersionState.DUPLICATE;
        }
        if (receivedModificationNumber > storedModificationNumber) {
            return EventVersionState.MODIFIED;
        }
        return EventVersionState.OUT_OF_SEQUENCE;
    }

    private EventProcessingResult successfulDuplicate(
            DrEvent existingEvent,
            String eventId,
            long modificationNumber
    ) {
        OptTypeType optType = existingEvent.getOptType() == DrEvent.OptType.OPT_OUT
                ? OptTypeType.OPT_OUT
                : OptTypeType.OPT_IN;
        log.info(
                "Duplicate OpenADR event version processed idempotently. eventId={}, modificationNumber={}",
                eventId,
                modificationNumber
        );
        return new EventProcessingResult(
                eventId,
                modificationNumber,
                ApplicationLayerErrorCodes.OK,
                optType
        );
    }

    private void saveOrUpdateEvent(
            OadrEvent oadrEvent,
            DrEvent existingEvent,
            OptTypeType optType,
            List<ParsedSignal> parsedSignals,
            String selectedSignalId
    ) {
        EventDescriptorType descriptor = requireDescriptor(oadrEvent);
        String eventId = requireEventId(descriptor);

        DrEvent drEvent = existingEvent != null ? existingEvent : new DrEvent();

        drEvent.setEventId(eventId);
        drEvent.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        DrEvent.EventStatus receivedStatus = mapStatus(descriptor);
        EventTiming timing = extractTiming(oadrEvent, existingEvent);

        drEvent.setStatus(receivedStatus);
        drEvent.setVtnStatus(receivedStatus);
        drEvent.setOptType(mapOptType(optType));
        drEvent.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        drEvent.setRequestedStartTime(timing.requestedStartTime());
        drEvent.setStartAfterSeconds(timing.startAfterSeconds());
        drEvent.setRandomOffsetSeconds(timing.randomOffsetSeconds());
        drEvent.setStartTime(timing.actualStartTime());
        drEvent.setRampUpSeconds(timing.rampUpSeconds());
        drEvent.setRecoverySeconds(timing.recoverySeconds());
        drEvent.setDurationSeconds(extractDurationSeconds(oadrEvent));
        drEvent.setExecutionStatus(optType == OptTypeType.OPT_IN
                ? DrEvent.ExecutionStatus.SCHEDULED
                : DrEvent.ExecutionStatus.RECEIVED);
        drEvent.setLastAppliedInterval(-1);
        drEvent.setAppliedAt(null);
        drEvent.setCompletedAt(null);
        drEvent.setCancellationType(null);
        drEvent.setCancellationRequestedAt(null);
        drEvent.setCancellationEffectiveAt(null);
        drEvent.replaceSignals(toSignalEntities(parsedSignals, selectedSignalId));
        drEvent.setUpdatedAt(Instant.now());

        drEventRepository.save(drEvent);
    }

    private void saveCancelledEvent(OadrEvent oadrEvent, DrEvent existingEvent) {
        EventDescriptorType descriptor = requireDescriptor(oadrEvent);
        String eventId = requireEventId(descriptor);

        DrEvent drEvent = existingEvent != null ? existingEvent : new DrEvent();

        drEvent.setEventId(eventId);
        drEvent.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        drEvent.setVtnStatus(DrEvent.EventStatus.CANCELLED);
        drEvent.setOptType(DrEvent.OptType.OPT_IN);
        drEvent.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        if (existingEvent == null) {
            EventTiming timing = extractTiming(oadrEvent, null);
            drEvent.setRequestedStartTime(timing.requestedStartTime());
            drEvent.setStartAfterSeconds(timing.startAfterSeconds());
            drEvent.setRandomOffsetSeconds(timing.randomOffsetSeconds());
            drEvent.setStartTime(timing.actualStartTime());
            drEvent.setRampUpSeconds(timing.rampUpSeconds());
            drEvent.setRecoverySeconds(timing.recoverySeconds());
            drEvent.setDurationSeconds(extractDurationSeconds(oadrEvent));
        }

        requestCancellation(drEvent, DrEvent.CancellationType.EXPLICIT);

        log.info("Accepted explicit OpenADR event cancellation. eventId={}, effectiveAt={}",
                eventId, drEvent.getCancellationEffectiveAt());
    }

    /**
     * Rule 61: every oadrDistributeEvent is the VTN's complete event snapshot.
     * Previously known pending/active events omitted from it are silently cancelled.
     */
    private void reconcileImplicitCancellations(Set<String> receivedEventIds) {
        List<DrEvent> knownEvents = drEventRepository.findAllByExecutionStatusIn(KNOWN_EVENT_STATUSES);
        if (knownEvents == null || knownEvents.isEmpty()) {
            return;
        }

        knownEvents.stream()
                .filter(event -> !receivedEventIds.contains(event.getEventId()))
                .forEach(event -> {
                    requestCancellation(event, DrEvent.CancellationType.IMPLICIT);
                    log.info(
                            "Implicitly cancelled OpenADR event omitted from snapshot. eventId={}, effectiveAt={}",
                            event.getEventId(), event.getCancellationEffectiveAt()
                    );
                });
    }

    /**
     * Rule 65: an already applied event with startafter terminates at a newly
     * randomized instant in [cancellation receipt, receipt + startafter]. A
     * pending event has no operational load to ramp out and is cancelled now,
     * which also guarantees that it cannot start after being omitted/cancelled.
     */
    private void requestCancellation(
            DrEvent event,
            DrEvent.CancellationType cancellationType
    ) {
        if (event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCEL_PENDING
                || event.getExecutionStatus() == DrEvent.ExecutionStatus.CANCELLED) {
            event.setVtnStatus(DrEvent.EventStatus.CANCELLED);
            if (event.getCancellationType() == null) {
                event.setCancellationType(cancellationType);
            }
            drEventRepository.save(event);
            return;
        }

        Instant requestedAt = Instant.now();
        boolean isOperationallyActive = event.getExecutionStatus() == DrEvent.ExecutionStatus.APPLIED
                || event.getLastAppliedInterval() >= 0
                || event.getAppliedAt() != null;
        long terminationOffsetSeconds = isOperationallyActive
                ? randomOffset(event.getStartAfterSeconds())
                : 0L;

        event.setVtnStatus(DrEvent.EventStatus.CANCELLED);
        event.setCancellationType(cancellationType);
        event.setCancellationRequestedAt(requestedAt);
        event.setCancellationEffectiveAt(requestedAt.plusSeconds(terminationOffsetSeconds));
        event.setUpdatedAt(requestedAt);

        if (isOperationallyActive) {
            event.setExecutionStatus(DrEvent.ExecutionStatus.CANCEL_PENDING);
        } else {
            event.setStatus(DrEvent.EventStatus.CANCELLED);
            event.setExecutionStatus(DrEvent.ExecutionStatus.CANCELLED);
            event.setCompletedAt(requestedAt);
        }

        drEventRepository.save(event);
    }

    private long randomOffset(Long windowSeconds) {
        if (windowSeconds == null || windowSeconds <= 0L) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(windowSeconds + 1L);
    }

    private void saveCompletedEvent(OadrEvent oadrEvent, DrEvent existingEvent) {
        EventDescriptorType descriptor = requireDescriptor(oadrEvent);
        String eventId = requireEventId(descriptor);

        DrEvent drEvent = existingEvent != null ? existingEvent : new DrEvent();

        drEvent.setEventId(eventId);
        drEvent.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        drEvent.setVtnStatus(DrEvent.EventStatus.COMPLETED);
        drEvent.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        if (existingEvent == null) {
            EventTiming timing = extractTiming(oadrEvent, null);
            drEvent.setStatus(DrEvent.EventStatus.COMPLETED);
            drEvent.setOptType(DrEvent.OptType.OPT_OUT);
            drEvent.setExecutionStatus(DrEvent.ExecutionStatus.COMPLETED);
            drEvent.setRequestedStartTime(timing.requestedStartTime());
            drEvent.setStartAfterSeconds(timing.startAfterSeconds());
            drEvent.setRandomOffsetSeconds(timing.randomOffsetSeconds());
            drEvent.setStartTime(timing.actualStartTime());
            drEvent.setRampUpSeconds(timing.rampUpSeconds());
            drEvent.setRecoverySeconds(timing.recoverySeconds());
            drEvent.setDurationSeconds(extractDurationSeconds(oadrEvent));
        }
        drEvent.setUpdatedAt(Instant.now());

        drEventRepository.save(drEvent);

        log.info("Saved completed OpenADR event. eventId={}", eventId);
    }

    private List<DrEventSignal> toSignalEntities(
            List<ParsedSignal> parsedSignals,
            String selectedSignalId
    ) {
        return java.util.stream.IntStream.range(0, parsedSignals.size())
                .mapToObj(sequence -> toSignalEntity(
                        parsedSignals.get(sequence), sequence, selectedSignalId))
                .toList();
    }

    private DrEventSignal toSignalEntity(
            ParsedSignal parsed,
            int sequence,
            String selectedSignalId
    ) {
        DrEventSignal entity = new DrEventSignal();
        entity.setSequenceNumber(sequence);
        entity.setSelectedForExecution(Objects.equals(parsed.signalId(), selectedSignalId));
        entity.setSignalId(parsed.signalId());
        entity.setSignalName(parsed.signalName());
        entity.setSignalType(parsed.signalType());
        entity.setCurrentValue(parsed.currentValue());
        entity.setItemBaseElement(parsed.itemBaseElement());
        entity.setItemBaseType(parsed.itemBaseType());
        entity.setItemUnits(parsed.itemUnits());
        entity.setSiScaleCode(parsed.siScaleCode());

        parsed.intervals().forEach(parsedInterval -> {
            DrEventInterval interval = new DrEventInterval();
            interval.setSequenceNumber(parsedInterval.sequenceNumber());
            interval.setIntervalUid(parsedInterval.uid());
            interval.setDurationSeconds(parsedInterval.durationSeconds());
            interval.setPayloadValue(parsedInterval.payloadValue());
            entity.addInterval(interval);
        });
        return entity;
    }

    private boolean requiresCreatedEventResponse(OadrEvent oadrEvent) {
        if (oadrEvent == null || oadrEvent.getOadrResponseRequired() == null) {
            return false;
        }

        return RESPONSE_REQUIRED_ALWAYS.equalsIgnoreCase(
                String.valueOf(oadrEvent.getOadrResponseRequired())
        );
    }

    private boolean isCancelled(EventDescriptorType descriptor) {
        return descriptor.getEventStatus() != null
                && DrEvent.EventStatus.CANCELLED.name()
                .equalsIgnoreCase(descriptor.getEventStatus().value());
    }

    private boolean isCompleted(EventDescriptorType descriptor) {
        return descriptor.getEventStatus() != null
                && DrEvent.EventStatus.COMPLETED.name()
                .equalsIgnoreCase(descriptor.getEventStatus().value());
    }

    private DrEvent.EventStatus mapStatus(EventDescriptorType descriptor) {
        if (descriptor.getEventStatus() == null || descriptor.getEventStatus().value() == null) {
            throw new IllegalArgumentException("eventStatus is required");
        }

        String status = descriptor.getEventStatus().value();

        return switch (status.toUpperCase()) {
            case "FAR" -> DrEvent.EventStatus.FAR;
            case "NEAR" -> DrEvent.EventStatus.NEAR;
            case "ACTIVE" -> DrEvent.EventStatus.ACTIVE;
            case "COMPLETED" -> DrEvent.EventStatus.COMPLETED;
            case "CANCELLED" -> DrEvent.EventStatus.CANCELLED;
            default -> throw new IllegalArgumentException("Unsupported eventStatus: " + status);
        };
    }

    private DrEvent.OptType mapOptType(OptTypeType optType) {
        return optType == OptTypeType.OPT_OUT
                ? DrEvent.OptType.OPT_OUT
                : DrEvent.OptType.OPT_IN;
    }

    private EventTiming extractTiming(OadrEvent oadrEvent, DrEvent existingEvent) {
        XMLGregorianCalendar dateTime = oadrEvent
                .getEiEvent()
                .getEiActivePeriod()
                .getProperties()
                .getDtstart()
                .getDateTime();

        Instant requestedStartTime = OpenAdrTimeUtils.fromXmlDateTime(dateTime);
        var activePeriodProperties = oadrEvent
                .getEiEvent()
                .getEiActivePeriod()
                .getProperties();
        String startAfter = null;
        var tolerance = activePeriodProperties.getTolerance();

        if (tolerance != null && tolerance.getTolerate() != null) {
            startAfter = tolerance.getTolerate().getStartafter();
        }

        long startAfterSeconds = durationSeconds(startAfter, 0L);
        if (startAfterSeconds < 0) {
            throw new IllegalArgumentException("startafter must not be negative");
        }

        long randomOffsetSeconds;
        if (existingEvent != null
                && Objects.equals(existingEvent.getStartAfterSeconds(), startAfterSeconds)) {
            randomOffsetSeconds = existingEvent.getRandomOffsetSeconds();
        } else if (startAfterSeconds == 0L) {
            randomOffsetSeconds = 0L;
        } else {
            randomOffsetSeconds = ThreadLocalRandom.current().nextLong(startAfterSeconds + 1L);
        }

        return new EventTiming(
                requestedStartTime,
                startAfterSeconds,
                randomOffsetSeconds,
                requestedStartTime.plusSeconds(randomOffsetSeconds),
                durationSeconds(
                        activePeriodProperties.getXEiRampUp() != null
                                ? activePeriodProperties.getXEiRampUp().getDuration()
                                : null,
                        null
                ),
                durationSeconds(
                        activePeriodProperties.getXEiRecovery() != null
                                ? activePeriodProperties.getXEiRecovery().getDuration()
                                : null,
                        null
                )
        );
    }

    private Long durationSeconds(String value, Long defaultValue) {
        return OpenAdrTimeUtils.parseOpenAdrDuration(value)
                .map(Duration::getSeconds)
                .orElse(defaultValue);
    }

    private Long extractDurationSeconds(OadrEvent oadrEvent) {
        var duration = oadrEvent
                .getEiEvent()
                .getEiActivePeriod()
                .getProperties()
                .getDuration();

        if (duration == null || duration.getDuration() == null) {
            return null;
        }

        return OpenAdrTimeUtils.parseOpenAdrDuration(duration.getDuration())
                .map(Duration::getSeconds)
                .orElse(null);
    }

    private EventDescriptorType requireDescriptor(OadrEvent oadrEvent) {
        EventDescriptorType descriptor = descriptorOf(oadrEvent);

        if (descriptor == null) {
            throw new EventValidationException(
                    "eventDescriptor is required",
                    ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER
            );
        }

        return descriptor;
    }

    private EventDescriptorType descriptorOf(OadrEvent oadrEvent) {
        if (oadrEvent == null || oadrEvent.getEiEvent() == null) {
            return null;
        }

        return oadrEvent.getEiEvent().getEventDescriptor();
    }

    private String requireEventId(EventDescriptorType descriptor) {
        if (descriptor.getEventID() == null || descriptor.getEventID().isBlank()) {
            throw new EventValidationException(
                    "eventID is required",
                    ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER
            );
        }

        return descriptor.getEventID();
    }

    private String safeRequestId(String requestId) {
        return requestId != null ? requestId : "";
    }

    private record EventProcessingResult(
            String eventId,
            long modificationNumber,
            int responseCode,
            OptTypeType optType
    ) {
    }

    private enum EventVersionState {
        NEW,
        MODIFIED,
        DUPLICATE,
        OUT_OF_SEQUENCE
    }

    private record EventTiming(
            Instant requestedStartTime,
            long startAfterSeconds,
            long randomOffsetSeconds,
            Instant actualStartTime,
            Long rampUpSeconds,
            Long recoverySeconds
    ) {
    }
}
