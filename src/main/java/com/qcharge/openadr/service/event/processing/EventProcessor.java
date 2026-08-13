package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.service.event.EventOptDecisionService;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.event.EventValidationService.ParsedSignal;
import com.qcharge.openadr.service.event.mapping.EventPayloadMapper;
import com.qcharge.openadr.service.event.store.EventStore;
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
import java.util.Set;

/** Application service that validates and persists one event independently of XML transport. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventProcessor {

    private final EventStore eventStore;
    private final EventVersionPolicy versionPolicy;
    private final EventValidationService validationService;
    private final EventResourceResolver resourceResolver;
    private final EventOptDecisionService optDecisionService;
    private final EventPayloadMapper payloadMapper;
    private final EventCancellationService cancellationService;
    private final Clock clock;

    public EventProcessingResult processSafely(OadrEvent event, Set<String> eventIds, String venId) {
        try {
            return process(event, eventIds, venId);
        } catch (EventValidationException exception) {
            return failure(event, exception.getResponseCode(), exception, false);
        } catch (TargetMismatchException exception) {
            return failure(event, ApplicationLayerErrorCodes.TARGET_MISMATCH, exception, false);
        } catch (IllegalArgumentException exception) {
            return failure(event, ApplicationLayerErrorCodes.INVALID_DATA, exception, false);
        } catch (Exception exception) {
            return failure(event, ApplicationLayerErrorCodes.INVALID_DATA, exception, true);
        }
    }

    private EventProcessingResult process(OadrEvent event, Set<String> eventIds, String venId) {
        EventDescriptorType descriptor = requireDescriptor(event);
        String eventId = requireEventId(descriptor);
        long modificationNumber = descriptor.getModificationNumber();

        if (!eventIds.add(eventId)) {
            throw new EventValidationException(
                    "eventID must be unique within oadrDistributeEvent: " + eventId,
                    ApplicationLayerErrorCodes.INVALID_ID);
        }

        DrEvent existing = eventStore.findByEventId(eventId).orElse(null);
        boolean unknownCancellation = existing == null && isStatus(descriptor, DrEvent.EventStatus.CANCELLED);
        EventVersionPolicy.State version = unknownCancellation
                ? EventVersionPolicy.State.NEW
                : versionPolicy.evaluate(existing, modificationNumber);

        if (version == EventVersionPolicy.State.OUT_OF_SEQUENCE) {
            log.warn("Out-of-sequence OpenADR event. eventId={}, stored={}, received={}",
                    eventId, existing != null ? existing.getModificationNumber() : null, modificationNumber);
            return result(eventId, modificationNumber,
                    ApplicationLayerErrorCodes.OUT_OF_SEQUENCE, OptTypeType.OPT_OUT);
        }
        if (version == EventVersionPolicy.State.DUPLICATE) {
            return processDuplicate(existing, descriptor, eventId, modificationNumber);
        }

        ResolvedEventTarget eventTarget = validateAndResolveTarget(event, venId);
        if (isStatus(descriptor, DrEvent.EventStatus.CANCELLED)) {
            if (unknownCancellation) {
                log.info("Ignoring cancellation for unknown OpenADR event. eventId={}", eventId);
                return result(eventId, modificationNumber, ApplicationLayerErrorCodes.OK, OptTypeType.OPT_IN);
            }
            applyCancellation(event, existing);
            return result(eventId, modificationNumber, ApplicationLayerErrorCodes.OK, OptTypeType.OPT_IN);
        }
        if (isStatus(descriptor, DrEvent.EventStatus.COMPLETED)) {
            applyCompletion(event, existing);
            OptTypeType optType = existing != null && existing.getOptType() == DrEvent.OptType.OPT_IN
                    ? OptTypeType.OPT_IN : OptTypeType.OPT_OUT;
            return result(eventId, modificationNumber, ApplicationLayerErrorCodes.OK, optType);
        }

        List<ParsedSignal> signals = validationService.parseSignals(event);
        Optional<ParsedSignal> selected = validationService.selectPreferredSignal(signals);
        if (selected.isEmpty()) {
            return result(eventId, modificationNumber,
                    ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED, OptTypeType.OPT_OUT);
        }

        Map<String, List<ResolvedResource>> resourcesBySignal = resourceResolver == null
                ? Map.of()
                : resourceResolver.resolveSignalTargets(
                        event, signals.stream().map(ParsedSignal::signalId).toList(), eventTarget);
        ParsedSignal selectedSignal = selected.get();
        OptTypeType optType = optDecisionService.determineOptType(event, selectedSignal);
        DrEvent aggregate = existing != null ? existing : new DrEvent();
        payloadMapper.applyExecutableEvent(
                aggregate,
                event,
                optType,
                signals,
                selectedSignal.signalId(),
                resourcesBySignal.getOrDefault(selectedSignal.signalId(), List.of()));
        eventStore.save(aggregate);

        log.info("OpenADR event persisted for lifecycle execution. eventId={}, optType={}", eventId, optType);
        return result(eventId, modificationNumber, ApplicationLayerErrorCodes.OK, optType);
    }

    private ResolvedEventTarget validateAndResolveTarget(OadrEvent event, String venId) {
        if (resourceResolver == null) {
            validationService.validateTargetAndMarketContext(event, venId);
            return null;
        }
        validationService.validateMarketContext(event);
        return resourceResolver.resolveEventTarget(event, venId);
    }

    private EventProcessingResult processDuplicate(
            DrEvent existing,
            EventDescriptorType descriptor,
            String eventId,
            long modificationNumber
    ) {
        if (isStatus(descriptor, DrEvent.EventStatus.COMPLETED)
                && existing.getVtnStatus() != DrEvent.EventStatus.COMPLETED) {
            existing.setVtnStatus(DrEvent.EventStatus.COMPLETED);
            eventStore.save(existing);
        }
        OptTypeType optType = existing.getOptType() == DrEvent.OptType.OPT_OUT
                ? OptTypeType.OPT_OUT : OptTypeType.OPT_IN;
        return result(eventId, modificationNumber, ApplicationLayerErrorCodes.OK, optType);
    }

    private void applyCancellation(OadrEvent event, DrEvent existing) {
        EventDescriptorType descriptor = requireDescriptor(event);
        existing.setEventId(descriptor.getEventID());
        existing.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        existing.setVtnStatus(DrEvent.EventStatus.CANCELLED);
        existing.setOptType(DrEvent.OptType.OPT_IN);
        existing.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        cancellationService.request(existing, DrEvent.CancellationType.EXPLICIT);
    }

    private void applyCompletion(OadrEvent event, DrEvent existing) {
        EventDescriptorType descriptor = requireDescriptor(event);
        DrEvent aggregate = existing != null ? existing : new DrEvent();
        aggregate.setEventId(descriptor.getEventID());
        aggregate.setModificationNumber(Math.toIntExact(descriptor.getModificationNumber()));
        aggregate.setVtnStatus(DrEvent.EventStatus.COMPLETED);
        aggregate.setPriority(descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null);
        aggregate.setTestEvent(existing != null
                ? existing.isTestEvent() : payloadMapper.isTestEvent(descriptor));
        if (existing == null) {
            aggregate.setStatus(DrEvent.EventStatus.COMPLETED);
            aggregate.setOptType(DrEvent.OptType.OPT_OUT);
            aggregate.setExecutionStatus(DrEvent.ExecutionStatus.COMPLETED);
            payloadMapper.initializeTerminalEvent(aggregate, event);
        }
        aggregate.setUpdatedAt(clock.instant());
        eventStore.save(aggregate);
    }

    private EventProcessingResult failure(OadrEvent event, int responseCode, Exception exception, boolean unexpected) {
        EventDescriptorType descriptor = descriptorOf(event);
        String eventId = descriptor != null && descriptor.getEventID() != null
                ? descriptor.getEventID() : "unknown";
        long modificationNumber = descriptor != null ? descriptor.getModificationNumber() : 0L;
        if (unexpected) {
            log.error("Failed to process OpenADR event. eventId={}, modificationNumber={}",
                    eventId, modificationNumber, exception);
        } else {
            log.warn("OpenADR event rejected. eventId={}, modificationNumber={}, responseCode={}, reason={}",
                    eventId, modificationNumber, responseCode, exception.getMessage());
        }
        return result(eventId, modificationNumber, responseCode, OptTypeType.OPT_OUT);
    }

    private EventProcessingResult result(String id, long version, int code, OptTypeType optType) {
        return new EventProcessingResult(id, version, code, optType);
    }

    private boolean isStatus(EventDescriptorType descriptor, DrEvent.EventStatus status) {
        return descriptor.getEventStatus() != null
                && status.name().equalsIgnoreCase(descriptor.getEventStatus().value());
    }

    private EventDescriptorType requireDescriptor(OadrEvent event) {
        EventDescriptorType descriptor = descriptorOf(event);
        if (descriptor == null) {
            throw new EventValidationException(
                    "eventDescriptor is required", ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER);
        }
        return descriptor;
    }

    private EventDescriptorType descriptorOf(OadrEvent event) {
        return event == null || event.getEiEvent() == null
                ? null : event.getEiEvent().getEventDescriptor();
    }

    private String requireEventId(EventDescriptorType descriptor) {
        if (descriptor.getEventID() == null || descriptor.getEventID().isBlank()) {
            throw new EventValidationException(
                    "eventID is required", ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER);
        }
        return descriptor.getEventID();
    }
}
