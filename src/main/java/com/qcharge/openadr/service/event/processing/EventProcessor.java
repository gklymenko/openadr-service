package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.model.enums.event.EventCancellationType;
import com.qcharge.openadr.model.enums.event.EventExecutionStatus;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.EventValidationService;
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
    private final EventValidationService validationService;
    private final EventResourceResolver resourceResolver;
    private final EventPayloadMapper payloadMapper;
    private final EventCancellationService cancellationService;
    private final Clock clock;

    public EventProcessingResult processSafely(ReceiveEventCommand event, String venId) {
        try {
            return process(event, venId);
        } catch (EventValidationException exception) {
            return failure(event, exception.getResponseCode(), exception, false);
        } catch (TargetMismatchException exception) {
            return failure(event, OpenADRResponseCode.TARGET_MISMATCH, exception, false);
        } catch (IllegalArgumentException exception) {
            return failure(event, OpenADRResponseCode.INVALID_DATA, exception, false);
        } catch (Exception exception) {
            return failure(event, OpenADRResponseCode.INVALID_DATA, exception, true);
        }
    }

    private EventProcessingResult process(ReceiveEventCommand event, String venId) {
        String eventId = event.eventId();
        long modificationNumber = event.modificationNumber();
        DrEvent existing = eventService.findByEventId(eventId).orElse(null);
        boolean unknownCancellation = existing == null && event.status() == EventStatus.CANCELLED;
        EventVersionPolicy.State version = unknownCancellation
                ? EventVersionPolicy.State.NEW
                : versionPolicy.evaluate(existing, modificationNumber);

        if (version == EventVersionPolicy.State.OUT_OF_SEQUENCE) {
            log.warn("Out-of-sequence OpenADR event. eventId={}, stored={}, received={}",
                    eventId, existing != null ? existing.getModificationNumber() : null,
                    modificationNumber);
            return result(eventId, modificationNumber,
                    OpenADRResponseCode.OUT_OF_SEQUENCE, EventOptType.OPT_OUT);
        }
        if (version == EventVersionPolicy.State.DUPLICATE) {
            return processDuplicate(existing, event);
        }

        ResolvedEventTarget eventTarget = validateAndResolveTarget(event, venId);
        if (event.status() == EventStatus.CANCELLED) {
            if (unknownCancellation) {
                log.info("Ignoring cancellation for unknown OpenADR event. eventId={}", eventId);
                return result(eventId, modificationNumber,
                        OpenADRResponseCode.OK, EventOptType.OPT_IN);
            }
            applyCancellation(event, existing);
            return result(eventId, modificationNumber,
                    OpenADRResponseCode.OK, EventOptType.OPT_IN);
        }
        if (event.status() == EventStatus.COMPLETED) {
            applyCompletion(event, existing);
            EventOptType optType = existing != null && existing.getOptType() == EventOptType.OPT_IN
                    ? EventOptType.OPT_IN : EventOptType.OPT_OUT;
            return result(eventId, modificationNumber, OpenADRResponseCode.OK, optType);
        }

        List<EventSignalCommand> signals = validationService.validateSignals(event);
        Optional<EventSignalCommand> selected = validationService.selectPreferredSignal(signals);
        if (selected.isEmpty()) {
            return result(eventId, modificationNumber,
                    OpenADRResponseCode.SIGNAL_NOT_SUPPORTED, EventOptType.OPT_OUT);
        }

        Map<String, List<ResolvedResource>> resourcesBySignal =
                resourceResolver.resolveSignalTargets(signals, eventTarget);
        EventSignalCommand selectedSignal = selected.get();
        EventOptType optType = EventOptType.OPT_IN;
        DrEvent aggregate = existing != null ? existing : new DrEvent();
        payloadMapper.applyExecutableEvent(
                aggregate, event, optType, signals, selectedSignal.signalId(),
                resourcesBySignal.getOrDefault(selectedSignal.signalId(), List.of()));
        eventService.save(aggregate);

        log.info("OpenADR event persisted for lifecycle execution. eventId={}, optType={}",
                eventId, optType);
        return result(eventId, modificationNumber, OpenADRResponseCode.OK, optType);
    }

    private ResolvedEventTarget validateAndResolveTarget(ReceiveEventCommand event, String venId) {
        validationService.validateMarketContext(event.marketContext());
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
        return result(event.eventId(), event.modificationNumber(),
                OpenADRResponseCode.OK, optType);
    }

    private void applyCancellation(ReceiveEventCommand event, DrEvent existing) {
        existing.setEventId(event.eventId());
        existing.setModificationNumber(Math.toIntExact(event.modificationNumber()));
        existing.setVtnStatus(EventStatus.CANCELLED);
        existing.setOptType(EventOptType.OPT_IN);
        existing.setPriority(event.priority());
        cancellationService.request(existing, EventCancellationType.EXPLICIT);
    }

    private void applyCompletion(ReceiveEventCommand event, DrEvent existing) {
        DrEvent aggregate = existing != null ? existing : new DrEvent();
        aggregate.setEventId(event.eventId());
        aggregate.setModificationNumber(Math.toIntExact(event.modificationNumber()));
        aggregate.setVtnStatus(EventStatus.COMPLETED);
        aggregate.setPriority(event.priority());
        aggregate.setTestEvent(existing != null ? existing.isTestEvent() : event.testEvent());
        if (existing == null) {
            aggregate.setStatus(EventStatus.COMPLETED);
            aggregate.setOptType(EventOptType.OPT_OUT);
            aggregate.setExecutionStatus(EventExecutionStatus.COMPLETED);
            payloadMapper.initializeTerminalEvent(aggregate, event);
        }
        aggregate.setUpdatedAt(clock.instant());
        eventService.save(aggregate);
    }

    private EventProcessingResult failure(
            ReceiveEventCommand event, int responseCode, Exception exception, boolean unexpected
    ) {
        if (unexpected) {
            log.error("Failed to process OpenADR event. eventId={}, modificationNumber={}",
                    event.eventId(), event.modificationNumber(), exception);
        } else {
            log.warn("OpenADR event rejected. eventId={}, modificationNumber={}, "
                            + "responseCode={}, reason={}",
                    event.eventId(), event.modificationNumber(), responseCode, exception.getMessage());
        }
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
