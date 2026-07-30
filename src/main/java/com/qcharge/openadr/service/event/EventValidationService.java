package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.ei.EiTargetType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventValidationService {

    public static final String SIGNAL_LOAD_DISPATCH = "LOAD_DISPATCH";
    public static final String SIGNAL_ELECTRICITY_PRICE = "ELECTRICITY_PRICE";
    public static final String SIGNAL_SIMPLE = "SIMPLE";

    /** Priority order: highest-value signal for EV chargers wins. */
    private static final List<String> SIGNAL_PRIORITY = List.of(
            SIGNAL_LOAD_DISPATCH,
            SIGNAL_ELECTRICITY_PRICE,
            SIGNAL_SIMPLE
    );

    private final OpenAdrProperties properties;
    public record ParsedSignal(String signalName, String signalType, BigDecimal currentValue) {}

    public void validateTargetAndMarketContext(OadrEvent oadrEvent) {
        validateTargetAndMarketContext(oadrEvent, properties.getVen().getId());
    }

    public void validateTargetAndMarketContext(
            OadrEvent oadrEvent,
            String venId
    ) {
        if (oadrEvent == null || oadrEvent.getEiEvent() == null) {
            throw new IllegalArgumentException("eiEvent is required");
        }

        validateTarget(oadrEvent, venId);
        validateMarketContext(oadrEvent);
    }

    public Optional<ParsedSignal> parseSignal(OadrEvent oadrEvent) {
        if (oadrEvent == null
                || oadrEvent.getEiEvent() == null
                || oadrEvent.getEiEvent().getEiEventSignals() == null
                || oadrEvent.getEiEvent().getEiEventSignals().getEiEventSignal().isEmpty()) {
            log.warn("Event has no eiEventSignals");
            return Optional.empty();
        }

        List<ParsedSignal> parsedSignals = oadrEvent.getEiEvent()
                .getEiEventSignals()
                .getEiEventSignal()
                .stream()
                .map(this::toParsedSignal)
                .toList();

        boolean hasUnsupportedSignal = parsedSignals.stream()
                .anyMatch(signal -> !isSupportedCombination(signal.signalName(), signal.signalType()));

        if (hasUnsupportedSignal) {
            log.warn("Unsupported event signal found. signals={}", parsedSignals);
            return Optional.empty();
        }

        for (String priorityName : SIGNAL_PRIORITY) {
            Optional<ParsedSignal> match = parsedSignals.stream()
                    .filter(signal -> priorityName.equalsIgnoreCase(signal.signalName()))
                    .findFirst();

            if (match.isPresent()) {
                log.debug(
                        "Matched signal: name={}, type={}, value={}",
                        match.get().signalName(),
                        match.get().signalType(),
                        match.get().currentValue()
                );
                return match;
            }
        }

        return Optional.empty();
    }

    private void validateTarget(OadrEvent oadrEvent, String venId) {
        EiTargetType target = oadrEvent.getEiEvent().getEiTarget();

        if (target == null) {
            throw new TargetMismatchException("eiTarget is missing");
        }

        boolean hasAnyTarget = hasAnyTarget(target);

        if (!hasAnyTarget) {
            if (properties.getEvent().isAllowUntargetedEvents()) {
                log.debug("Event has empty eiTarget. Treating as broadcast to this VEN.");
                return;
            }

            throw new TargetMismatchException("eiTarget is empty");
        }

        boolean hasVenTarget = !target.getVenID().isEmpty();
        boolean hasResourceTarget = !target.getResourceID().isEmpty();

        boolean venMatches = hasVenTarget && containsIgnoreCase(
                target.getVenID(),
                venId
        );

        boolean resourceMatches = hasResourceTarget && containsIgnoreCase(
                target.getResourceID(),
                properties.getReport().getResourceId()
        );

        if (venMatches || resourceMatches) {
            if (hasUnsupportedTargetDimensions(target)) {
                log.debug(
                        "Event target matched supported dimension, unsupported target dimensions will be ignored. venIDs={}, resourceIDs={}",
                        target.getVenID(),
                        target.getResourceID()
                );
            }
            return;
        }

        throw new TargetMismatchException(
                "Event target mismatch. venIDs=%s, resourceIDs=%s"
                        .formatted(target.getVenID(), target.getResourceID())
        );
    }

    private void validateMarketContext(OadrEvent oadrEvent) {
        String marketContext = marketContextOf(oadrEvent);

        List<String> allowedMarketContexts = properties.getEvent().getAllowedMarketContexts();

        if (allowedMarketContexts == null || allowedMarketContexts.isEmpty()) {
            return;
        }

        if (!containsIgnoreCase(allowedMarketContexts, marketContext)) {
            throw new TargetMismatchException(
                    "Unsupported marketContext: " + marketContext
            );
        }
    }

    private String marketContextOf(OadrEvent oadrEvent) {
        EventDescriptorType descriptor = oadrEvent.getEiEvent().getEventDescriptor();

        if (descriptor == null
                || descriptor.getEiMarketContext() == null
                || descriptor.getEiMarketContext().getMarketContext() == null
                || descriptor.getEiMarketContext().getMarketContext().isBlank()) {
            throw new IllegalArgumentException("marketContext is required");
        }

        return descriptor.getEiMarketContext().getMarketContext();
    }

    private boolean hasAnyTarget(EiTargetType target) {
        return !target.getVenID().isEmpty()
                || !target.getResourceID().isEmpty()
                || !target.getGroupID().isEmpty()
                || !target.getGroupName().isEmpty()
                || !target.getPartyID().isEmpty()
                || !target.getAggregatedPnode().isEmpty()
                || !target.getEndDeviceAsset().isEmpty()
                || !target.getMeterAsset().isEmpty()
                || !target.getPnode().isEmpty()
                || !target.getServiceArea().isEmpty()
                || !target.getServiceDeliveryPoint().isEmpty()
                || !target.getServiceLocation().isEmpty()
                || !target.getTransportInterface().isEmpty();
    }

    private boolean hasUnsupportedTargetDimensions(EiTargetType target) {
        return !target.getGroupID().isEmpty()
                || !target.getGroupName().isEmpty()
                || !target.getPartyID().isEmpty()
                || !target.getAggregatedPnode().isEmpty()
                || !target.getEndDeviceAsset().isEmpty()
                || !target.getMeterAsset().isEmpty()
                || !target.getPnode().isEmpty()
                || !target.getServiceArea().isEmpty()
                || !target.getServiceDeliveryPoint().isEmpty()
                || !target.getServiceLocation().isEmpty()
                || !target.getTransportInterface().isEmpty();
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private boolean isSupportedCombination(String signalName, String signalType) {
        if (signalName == null) {
            return false;
        }

        return switch (signalName.toUpperCase()) {
            case SIGNAL_SIMPLE -> "level".equalsIgnoreCase(signalType);
            case SIGNAL_ELECTRICITY_PRICE -> "price".equalsIgnoreCase(signalType);
            case SIGNAL_LOAD_DISPATCH -> "setpoint".equalsIgnoreCase(signalType);
            default -> false;
        };
    }

    private ParsedSignal toParsedSignal(EiEventSignalType signal) {
        String signalType = signal.getSignalType() != null
                ? signal.getSignalType().value()
                : null;

        BigDecimal value = null;

        if (signal.getCurrentValue() != null
                && signal.getCurrentValue().getPayloadFloat() != null) {
            value = BigDecimal.valueOf(signal.getCurrentValue().getPayloadFloat().getValue());
        }

        return new ParsedSignal(signal.getSignalName(), signalType, value);
    }
}
