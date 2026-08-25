package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.model.enums.event.EventCancellationType;
import com.qcharge.openadr.model.enums.event.EventExecutionStatus;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.EventPolicyService;
import com.qcharge.openadr.model.enums.event.EventOptType;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import com.qcharge.openadr.service.event.mapping.EventPayloadMapper;
import com.qcharge.openadr.service.event.store.EventService;
import com.qcharge.openadr.service.resource.EventResourceResolver;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedEventTarget;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Processes one normalized event command without depending on OpenADR/JAXB types. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventProcessor {

    private final EventService eventService;
    private final EventVersionPolicy versionPolicy;
    private final EventPolicyService eventPolicyService;
    private final EventResourceResolver resourceResolver;
    private final EventPayloadMapper payloadMapper;
    private final EventCancellationService cancellationService;
    private final Clock clock;

    /**
     * Converts expected VTN event errors to per-event responses. Unexpected failures escape so
     * the surrounding snapshot transaction is rolled back.
     */
    public EventProcessingResult process(ReceiveEventCommand event, String venId) {
        try {
            return processValidated(event, venId);
        } catch (EventValidationException exception) {
            return failure(event, exception.getResponseCode(), exception);
        } catch (TargetMismatchException exception) {
            return failure(event, OpenADRResponseCode.TARGET_MISMATCH, exception);
        }
    }

    private EventProcessingResult processValidated(ReceiveEventCommand event, String venId) {
        String eventId = event.eventId();
        long modificationNumber = event.modificationNumber();
        Optional<DrEvent> existing = eventService.findByEventId(eventId);

        boolean unknownCancellation = existing.isEmpty()
                && event.status() == EventStatus.CANCELLED;
        EventVersionPolicy.State version = existing
                .map(stored -> versionPolicy.evaluate(stored, modificationNumber))
                .orElse(EventVersionPolicy.State.NEW);

        if (version == EventVersionPolicy.State.OUT_OF_SEQUENCE) {
            log.warn("Out-of-sequence OpenADR event. eventId={}, stored={}, received={}",
                    eventId,
                    existing.map(DrEvent::getModificationNumber).orElse(null),
                    modificationNumber);
            return result(eventId, modificationNumber,
                    OpenADRResponseCode.OUT_OF_SEQUENCE, EventOptType.OPT_OUT);
        }
        if (version == EventVersionPolicy.State.DUPLICATE) {
            return processDuplicate(requireExisting(existing, eventId, version), event);
        }

        ResolvedEventTarget eventTarget = validateAndResolveTarget(event, venId);
        if (event.status() == EventStatus.CANCELLED) {
            if (unknownCancellation) {
                log.info("Ignoring cancellation for unknown OpenADR event. eventId={}", eventId);
                return result(eventId, modificationNumber,
                        OpenADRResponseCode.OK, EventOptType.OPT_IN);
            }
            applyCancellation(
                    event,
                    requireExisting(existing, eventId, version)
            );
            return result(eventId, modificationNumber,
                    OpenADRResponseCode.OK, EventOptType.OPT_IN);
        }
        if (event.status() == EventStatus.COMPLETED) {
            boolean newEvent = existing.isEmpty();
            DrEvent aggregate = existing.orElseGet(DrEvent::new);
            applyCompletion(event, aggregate, newEvent);
            EventOptType optType = existing
                    .map(DrEvent::getOptType)
                    .filter(EventOptType.OPT_IN::equals)
                    .map(ignored -> EventOptType.OPT_IN)
                    .orElse(EventOptType.OPT_OUT);
            return result(eventId, modificationNumber, OpenADRResponseCode.OK, optType);
        }

        List<EventSignalCommand> signals = eventPolicyService.supportedSignals(event);
        Optional<EventSignalCommand> selected = eventPolicyService.selectPreferredSignal(signals);
        if (selected.isEmpty()) {
            return result(eventId, modificationNumber,
                    OpenADRResponseCode.SIGNAL_NOT_SUPPORTED, EventOptType.OPT_OUT);
        }

        Map<String, List<ResolvedResource>> resourcesBySignal =
                resourceResolver.resolveSignalTargets(signals, eventTarget);
        EventSignalCommand selectedSignal = selected.get();
        EventOptType optType = EventOptType.OPT_IN;
        DrEvent aggregate = existing.orElseGet(DrEvent::new);
        payloadMapper.applyExecutableEvent(
                aggregate, event, optType, signals, selectedSignal.signalId(),
                resourcesBySignal.getOrDefault(selectedSignal.signalId(), List.of()));
        eventService.save(aggregate);

        log.info("OpenADR event persisted for lifecycle execution. eventId={}, optType={}",
                eventId, optType);
        return result(eventId, modificationNumber, OpenADRResponseCode.OK, optType);
    }

    private ResolvedEventTarget validateAndResolveTarget(ReceiveEventCommand event, String venId) {
        eventPolicyService.requireAllowedMarketContext(event.marketContext());
        return resourceResolver.resolveEventTarget(event.target(), venId);
    }

    private EventProcessingResult processDuplicate(DrEvent existing, ReceiveEventCommand event) {
        if (event.status() == EventStatus.COMPLETED
                && existing.getVtnStatus() != EventStatus.COMPLETED) {
            existing.setVtnStatus(EventStatus.COMPLETED);
            eventService.save(existing);
        }
        EventOptType optType = existing.getOptType() == EventOptType.OPT_OUT
                ? EventOptType.OPT_OUT : EventOptType.OPT_IN;
        log.info(
                "Processed duplicate OpenADR event version. eventId={}, modificationNumber={}, "
                        + "vtnStatus={}, optType={}",
                event.eventId(),
                event.modificationNumber(),
                existing.getVtnStatus(),
                optType
        );
        return result(event.eventId(), event.modificationNumber(),
                OpenADRResponseCode.OK, optType);
    }

    private DrEvent requireExisting(
            Optional<DrEvent> existing,
            String eventId,
            EventVersionPolicy.State version
    ) {
        return existing.orElseThrow(() -> new IllegalStateException(
                "Stored OpenADR event is required for state=%s, eventId=%s"
                        .formatted(version, eventId)
        ));
    }

    private void applyCancellation(ReceiveEventCommand event, DrEvent existing) {
        existing.setEventId(event.eventId());
        existing.setModificationNumber(Math.toIntExact(event.modificationNumber()));
        existing.setVtnStatus(EventStatus.CANCELLED);
        existing.setOptType(EventOptType.OPT_IN);
        existing.setPriority(event.priority());
        cancellationService.request(existing, EventCancellationType.EXPLICIT);
    }

    private void applyCompletion(
            ReceiveEventCommand event,
            DrEvent aggregate,
            boolean newEvent
    ) {
        aggregate.setEventId(event.eventId());
        aggregate.setModificationNumber(Math.toIntExact(event.modificationNumber()));
        aggregate.setVtnStatus(EventStatus.COMPLETED);
        aggregate.setPriority(event.priority());
        aggregate.setTestEvent(newEvent ? event.testEvent() : aggregate.isTestEvent());
        if (newEvent) {
            aggregate.setVenStatus(EventStatus.COMPLETED);
            aggregate.setOptType(EventOptType.OPT_OUT);
            aggregate.setExecutionStatus(EventExecutionStatus.COMPLETED);
            payloadMapper.initializeTerminalEvent(aggregate, event);
        }
        aggregate.setUpdatedAt(clock.instant());
        eventService.save(aggregate);
    }

    private EventProcessingResult failure(
            ReceiveEventCommand event, int responseCode, Exception exception
    ) {
        log.warn("OpenADR event rejected. eventId={}, modificationNumber={}, "
                        + "responseCode={}, reason={}",
                event.eventId(), event.modificationNumber(), responseCode, exception.getMessage());
        return result(event.eventId(), event.modificationNumber(),
                responseCode, EventOptType.OPT_OUT);
    }

    private EventProcessingResult result(
            String id,
            long version,
            int code,
            EventOptType optType
    ) {
        return new EventProcessingResult(id, version, code, optType);
    }
}
