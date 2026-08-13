package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.service.event.command.EventIntervalCommand;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.event.command.EventStatus;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Validates normalized VEN event commands without depending on OpenADR/JAXB types. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventValidationService {

    public static final String SIGNAL_LOAD_DISPATCH = "LOAD_DISPATCH";
    public static final String SIGNAL_ELECTRICITY_PRICE = "ELECTRICITY_PRICE";
    public static final String SIGNAL_SIMPLE = "SIMPLE";

    private static final List<String> SIGNAL_PRIORITY = List.of(
            SIGNAL_LOAD_DISPATCH, SIGNAL_ELECTRICITY_PRICE, SIGNAL_SIMPLE);

    private final OpenAdrProperties properties;

    public void validateMarketContext(String marketContext) {
        if (marketContext == null || marketContext.isBlank()) {
            throw new IllegalArgumentException("marketContext is required");
        }
        List<String> allowed = properties.getEvent().getAllowedMarketContexts();
        if (allowed != null && !allowed.isEmpty() && !containsIgnoreCase(allowed, marketContext)) {
            throw new TargetMismatchException("Unsupported marketContext: " + marketContext);
        }
    }

    /** Empty means an unsupported signal was found and Rule 109 opts out of the event. */
    public List<EventSignalCommand> validateSignals(ReceiveEventCommand event) {
        List<EventSignalCommand> signals = event.signals();
        if (signals.isEmpty()) {
            log.warn("Event has no eiEventSignals");
            return List.of();
        }
        if (signals.stream().anyMatch(signal -> !isSupportedCombination(signal))) {
            log.warn("Unsupported event signal found. signals={}", signals);
            return List.of();
        }
        validateUniqueSignalIds(signals);
        validateIntervals(event.timing().durationSeconds(), signals);
        validateSignalValues(event.status(), signals);
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

    private void validateUniqueSignalIds(List<EventSignalCommand> signals) {
        Set<String> ids = new HashSet<>();
        for (EventSignalCommand signal : signals) {
            if (signal.signalId() == null || signal.signalId().isBlank()) {
                throw complianceError("signalID is required");
            }
            if (!ids.add(signal.signalId())) {
                throw complianceError("signalID must be unique within an event: " + signal.signalId());
            }
        }
    }

    private void validateIntervals(long eventDurationSeconds, List<EventSignalCommand> signals) {
        boolean openEnded = eventDurationSeconds == 0L;
        for (EventSignalCommand signal : signals) {
            for (EventIntervalCommand interval : signal.intervals()) {
                if (interval.explicitStart()) {
                    throw complianceError("Interval dtstart is not allowed for signal " + signal.signalId());
                }
                String expectedUid = Integer.toString(interval.sequenceNumber());
                if (!expectedUid.equals(interval.uid())) {
                    throw complianceError("Interval uid for signal %s must be %s but was %s"
                            .formatted(signal.signalId(), expectedUid, interval.uid()));
                }
                if (interval.durationSeconds() < 0L
                        || (interval.durationSeconds() == 0L && !openEnded)) {
                    throw complianceError(
                            "Interval duration must be positive unless the event is open-ended "
                                    + "for signal %s, uid=%s"
                                    .formatted(signal.signalId(), interval.uid()));
                }
            }
            if (!openEnded) {
                long intervalsDuration = signal.intervals().stream()
                        .mapToLong(EventIntervalCommand::durationSeconds).sum();
                if (intervalsDuration != eventDurationSeconds) {
                    throw complianceError(
                            "interval durations for signal %s sum to %d seconds; event duration is %d seconds"
                                    .formatted(signal.signalId(), intervalsDuration, eventDurationSeconds));
                }
            }
        }
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

    private void validateSignalValues(EventStatus status, List<EventSignalCommand> signals) {
        for (EventSignalCommand signal : signals) {
            if (!SIGNAL_SIMPLE.equalsIgnoreCase(signal.signalName())) {
                continue;
            }
            signal.intervals().forEach(interval -> validateSimpleLevel(
                    interval.payloadValue(), "SIMPLE interval uid=" + interval.uid()));
            if (signal.currentValue() != null) {
                validateSimpleLevel(signal.currentValue(), "SIMPLE currentValue");
                if (status != EventStatus.ACTIVE
                        && signal.currentValue().compareTo(BigDecimal.ZERO) != 0) {
                    throw invalidData("SIMPLE currentValue must be 0 while event is not active");
                }
            }
        }
    }

    private void validateSimpleLevel(BigDecimal value, String subject) {
        if (value == null || value.stripTrailingZeros().scale() > 0
                || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.valueOf(3)) > 0) {
            throw invalidData(subject + " must be one of 0, 1, 2, 3");
        }
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private EventValidationException complianceError(String message) {
        return new EventValidationException(
                message, ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER);
    }

    private EventValidationException invalidData(String message) {
        return new EventValidationException(message, ApplicationLayerErrorCodes.INVALID_DATA);
    }
}
