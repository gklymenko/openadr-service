package com.qcharge.openadr.service.event;

import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
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

    public record ParsedSignal(String signalName, String signalType, BigDecimal currentValue) {}

    /**
     * Finds the best supported signal from the event's eiEventSignals list.
     * Returns empty if no supported signal is found (Rule 109 → respond 460).
     */
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
                .map(this::toParseSignal)
                .toList();

        boolean hasUnsupported = parsedSignals.stream()
                .anyMatch(signal -> !isSupportedCombination(signal.signalName(), signal.signalType()));

        if (hasUnsupported) {
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

    private ParsedSignal toParseSignal(EiEventSignalType signal) {
        String signalType = signal.getSignalType() != null ? signal.getSignalType().value() : null;
        BigDecimal value = null;

        if (signal.getCurrentValue() != null
                && signal.getCurrentValue().getPayloadFloat() != null) {
            value = BigDecimal.valueOf(signal.getCurrentValue().getPayloadFloat().getValue());
        }

        return new ParsedSignal(signal.getSignalName(), signalType, value);
    }
}