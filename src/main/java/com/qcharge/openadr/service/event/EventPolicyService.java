package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Applies local VEN market-context and signal capability policy to validated event commands. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPolicyService {

    public static final String SIGNAL_LOAD_DISPATCH = "LOAD_DISPATCH";
    public static final String SIGNAL_ELECTRICITY_PRICE = "ELECTRICITY_PRICE";
    public static final String SIGNAL_SIMPLE = "SIMPLE";

    private static final List<String> SIGNAL_PRIORITY = List.of(
            SIGNAL_LOAD_DISPATCH, SIGNAL_ELECTRICITY_PRICE, SIGNAL_SIMPLE);

    private final OpenAdrProperties properties;

    public void requireAllowedMarketContext(String marketContext) {
        List<String> allowed = properties.getEvent().getAllowedMarketContexts();
        if (allowed != null && !allowed.isEmpty() && !containsIgnoreCase(allowed, marketContext)) {
            throw new TargetMismatchException("Unsupported marketContext: " + marketContext);
        }
    }

    /** Empty means an unsupported signal was found and Rule 109 requires opting out. */
    public List<EventSignalCommand> supportedSignals(ReceiveEventCommand event) {
        List<EventSignalCommand> signals = event.signals();
        if (signals.isEmpty()) {
            log.warn("Event has no eiEventSignals");
            return List.of();
        }
        if (signals.stream().anyMatch(signal -> !isSupportedCombination(signal))) {
            log.warn("Unsupported event signal found. signals={}", signals);
            return List.of();
        }
        return List.copyOf(signals);
    }

    public Optional<EventSignalCommand> selectPreferredSignal(List<EventSignalCommand> signals) {
        for (String priorityName : SIGNAL_PRIORITY) {
            Optional<EventSignalCommand> match = signals.stream()
                    .filter(signal -> priorityName.equalsIgnoreCase(signal.signalName()))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private boolean isSupportedCombination(EventSignalCommand signal) {
        if (signal.signalName() == null) {
            return false;
        }
        return switch (signal.signalName().toUpperCase()) {
            case SIGNAL_SIMPLE -> "level".equalsIgnoreCase(signal.signalType())
                    && signal.itemBaseElement() == null;
            case SIGNAL_ELECTRICITY_PRICE -> "price".equalsIgnoreCase(signal.signalType())
                    && "currencyPerKWh".equalsIgnoreCase(signal.itemBaseElement())
                    && hasUnitsAndScale(signal);
            case SIGNAL_LOAD_DISPATCH -> "setpoint".equalsIgnoreCase(signal.signalType())
                    && "powerReal".equalsIgnoreCase(signal.itemBaseElement())
                    && isRealPowerUnit(signal.itemUnits()) && hasUnitsAndScale(signal);
            default -> false;
        };
    }

    private boolean hasUnitsAndScale(EventSignalCommand signal) {
        return signal.itemUnits() != null && !signal.itemUnits().isBlank()
                && signal.siScaleCode() != null && !signal.siScaleCode().isBlank();
    }

    private boolean isRealPowerUnit(String units) {
        return "W".equalsIgnoreCase(units) || "J/s".equalsIgnoreCase(units);
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.equalsIgnoreCase(expected));
    }

}
