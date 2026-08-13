package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.PayloadFloatType;
import com.qcharge.openadr.model.oadr20b.ei.SignalPayloadType;
import com.qcharge.openadr.model.oadr20b.emix.ItemBaseType;
import com.qcharge.openadr.model.oadr20b.oadr.CurrencyType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.power.PowerRealType;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import jakarta.xml.bind.JAXBElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    public record ParsedInterval(
            String uid,
            int sequenceNumber,
            long durationSeconds,
            BigDecimal payloadValue
    ) {}

    public record ParsedSignal(
            String signalId,
            String signalName,
            String signalType,
            BigDecimal currentValue,
            String itemBaseElement,
            String itemBaseType,
            String itemUnits,
            String siScaleCode,
            List<ParsedInterval> intervals
    ) {
        public ParsedSignal {
            intervals = List.copyOf(intervals);
        }
    }

    public void validateMarketContext(OadrEvent oadrEvent) {
        if (oadrEvent == null || oadrEvent.getEiEvent() == null) {
            throw new IllegalArgumentException("eiEvent is required");
        }
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

    /**
     * @deprecated Event targeting is registry-backed and is validated by
     * {@link com.qcharge.openadr.service.resource.EventResourceResolver}.
     */
    @Deprecated(forRemoval = false)
    public void validateTargetAndMarketContext(OadrEvent oadrEvent) {
        validateMarketContext(oadrEvent);
    }

    /**
     * @deprecated Event targeting is registry-backed and is validated by
     * {@link com.qcharge.openadr.service.resource.EventResourceResolver}.
     */
    @Deprecated(forRemoval = false)
    public void validateTargetAndMarketContext(OadrEvent oadrEvent, String venId) {
        validateMarketContext(oadrEvent);
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

    public Optional<ParsedSignal> parseSignal(OadrEvent oadrEvent) {
        return selectPreferredSignal(parseSignals(oadrEvent));
    }

    /**
     * Parses the complete execution plan. Returning an empty list means that at least one
     * requested signal is unsupported, so Rule 109 requires opting out of the whole event.
     */
    public List<ParsedSignal> parseSignals(OadrEvent oadrEvent) {
        if (oadrEvent == null
                || oadrEvent.getEiEvent() == null
                || oadrEvent.getEiEvent().getEiEventSignals() == null
                || oadrEvent.getEiEvent().getEiEventSignals().getEiEventSignal().isEmpty()) {
            log.warn("Event has no eiEventSignals");
            return List.of();
        }

        long eventDurationSeconds = eventDurationSeconds(oadrEvent);
        boolean openEnded = eventDurationSeconds == 0L;

        List<ParsedSignal> parsedSignals = oadrEvent.getEiEvent()
                .getEiEventSignals()
                .getEiEventSignal()
                .stream()
                .map(signal -> toParsedSignal(signal, openEnded))
                .toList();

        boolean hasUnsupportedSignal = parsedSignals.stream()
                .anyMatch(signal -> !isSupportedCombination(signal));

        if (hasUnsupportedSignal) {
            log.warn("Unsupported event signal found. signals={}", parsedSignals);
            return List.of();
        }

        validateUniqueSignalIds(parsedSignals);
        validateIntervalDurations(eventDurationSeconds, parsedSignals);
        validateSignalValues(oadrEvent, parsedSignals);
        return List.copyOf(parsedSignals);
    }

    public Optional<ParsedSignal> selectPreferredSignal(List<ParsedSignal> parsedSignals) {
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

    private void validateUniqueSignalIds(List<ParsedSignal> signals) {
        Set<String> ids = new HashSet<>();
        for (ParsedSignal signal : signals) {
            if (signal.signalId() == null || signal.signalId().isBlank()) {
                throw complianceError("signalID is required");
            }
            if (!ids.add(signal.signalId())) {
                throw complianceError("signalID must be unique within an event: " + signal.signalId());
            }
        }
    }

    private void validateIntervalDurations(long eventDurationSeconds, List<ParsedSignal> signals) {
        if (eventDurationSeconds == 0L) {
            return;
        }

        for (ParsedSignal signal : signals) {
            long intervalsDuration = signal.intervals().stream()
                    .mapToLong(ParsedInterval::durationSeconds)
                    .sum();
            if (intervalsDuration != eventDurationSeconds) {
                throw complianceError(
                        "interval durations for signal %s sum to %d seconds; event duration is %d seconds"
                                .formatted(signal.signalId(), intervalsDuration, eventDurationSeconds)
                );
            }
        }
    }

    private long eventDurationSeconds(OadrEvent event) {
        try {
            String value = event.getEiEvent()
                    .getEiActivePeriod()
                    .getProperties()
                    .getDuration()
                    .getDuration();
            return parseDurationSeconds(value, "event");
        } catch (NullPointerException exception) {
            throw complianceError("Event duration is required");
        }
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        if (expected == null || expected.isBlank()) {
            return false;
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private boolean isSupportedCombination(ParsedSignal signal) {
        String signalName = signal.signalName();
        String signalType = signal.signalType();
        if (signalName == null) {
            return false;
        }

        return switch (signalName.toUpperCase()) {
            case SIGNAL_SIMPLE -> "level".equalsIgnoreCase(signalType)
                    && signal.itemBaseElement() == null;
            case SIGNAL_ELECTRICITY_PRICE -> "price".equalsIgnoreCase(signalType)
                    && "currencyPerKWh".equalsIgnoreCase(signal.itemBaseElement())
                    && hasUnitsAndScale(signal);
            case SIGNAL_LOAD_DISPATCH -> "setpoint".equalsIgnoreCase(signalType)
                    && "powerReal".equalsIgnoreCase(signal.itemBaseElement())
                    && isRealPowerUnit(signal.itemUnits())
                    && hasUnitsAndScale(signal);
            default -> false;
        };
    }

    private boolean hasUnitsAndScale(ParsedSignal signal) {
        return signal.itemUnits() != null
                && !signal.itemUnits().isBlank()
                && signal.siScaleCode() != null
                && !signal.siScaleCode().isBlank();
    }

    private boolean isRealPowerUnit(String itemUnits) {
        return "W".equalsIgnoreCase(itemUnits) || "J/s".equalsIgnoreCase(itemUnits);
    }

    private void validateSignalValues(OadrEvent event, List<ParsedSignal> signals) {
        for (ParsedSignal signal : signals) {
            if (!SIGNAL_SIMPLE.equalsIgnoreCase(signal.signalName())) {
                continue;
            }

            signal.intervals().forEach(interval -> validateSimpleLevel(
                    interval.payloadValue(),
                    "SIMPLE interval uid=" + interval.uid()
            ));

            if (signal.currentValue() != null) {
                validateSimpleLevel(signal.currentValue(), "SIMPLE currentValue");

                EventDescriptorType descriptor = event.getEiEvent().getEventDescriptor();
                boolean active = descriptor != null
                        && descriptor.getEventStatus() != null
                        && "active".equalsIgnoreCase(descriptor.getEventStatus().value());
                if (!active && signal.currentValue().compareTo(BigDecimal.ZERO) != 0) {
                    throw invalidData("SIMPLE currentValue must be 0 while event is not active");
                }
            }
        }
    }

    private void validateSimpleLevel(BigDecimal value, String subject) {
        if (value == null
                || value.stripTrailingZeros().scale() > 0
                || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.valueOf(3)) > 0) {
            throw invalidData(subject + " must be one of 0, 1, 2, 3");
        }
    }

    private ParsedSignal toParsedSignal(EiEventSignalType signal, boolean openEnded) {
        String signalType = signal.getSignalType() != null
                ? signal.getSignalType().value()
                : null;

        BigDecimal value = null;

        if (signal.getCurrentValue() != null
                && signal.getCurrentValue().getPayloadFloat() != null) {
            value = decimal(signal.getCurrentValue().getPayloadFloat().getValue());
        }

        ItemBaseType itemBase = signal.getItemBase() != null
                ? signal.getItemBase().getValue()
                : null;
        String itemBaseElement = itemBaseElement(signal, itemBase);
        String itemBaseType = itemBase != null ? itemBase.getClass().getSimpleName() : null;
        String itemUnits = itemUnits(itemBase);
        String siScaleCode = siScaleCode(itemBase);

        List<IntervalType> sourceIntervals = signal.getIntervals() != null
                ? signal.getIntervals().getInterval()
                : List.of();
        if (sourceIntervals.isEmpty()) {
            throw complianceError("At least one interval is required for signal " + signal.getSignalID());
        }

        List<ParsedInterval> intervals = new ArrayList<>();
        for (int sequence = 0; sequence < sourceIntervals.size(); sequence++) {
            intervals.add(toParsedInterval(
                    signal.getSignalID(), sourceIntervals.get(sequence), sequence, openEnded));
        }

        return new ParsedSignal(
                signal.getSignalID(),
                signal.getSignalName(),
                signalType,
                value,
                itemBaseElement,
                itemBaseType,
                itemUnits,
                siScaleCode,
                intervals
        );
    }

    private ParsedInterval toParsedInterval(
            String signalId,
            IntervalType interval,
            int sequence,
            boolean openEnded
    ) {
        if (interval.getDtstart() != null) {
            throw complianceError("Interval dtstart is not allowed for signal " + signalId);
        }

        String uid = interval.getUid() != null ? interval.getUid().getText() : null;
        String expectedUid = Integer.toString(sequence);
        if (!expectedUid.equals(uid)) {
            throw complianceError(
                    "Interval uid for signal %s must be %s but was %s"
                            .formatted(signalId, expectedUid, uid)
            );
        }

        String durationValue = interval.getDuration() != null
                ? interval.getDuration().getDuration()
                : null;
        long durationSeconds = parseDurationSeconds(
                durationValue,
                "interval for signal %s, uid=%s".formatted(signalId, uid)
        );
        if (durationSeconds < 0 || (durationSeconds == 0 && !openEnded)) {
            throw complianceError(
                    "Interval duration must be positive unless the event is open-ended "
                            + "for signal %s, uid=%s"
                            .formatted(signalId, uid)
            );
        }

        List<JAXBElement<? extends com.qcharge.openadr.model.oadr20b.strm.StreamPayloadBaseType>> payloads =
                interval.getStreamPayloadBase();
        if (payloads.size() != 1 || !(payloads.getFirst().getValue() instanceof SignalPayloadType payload)) {
            throw complianceError(
                    "Exactly one signalPayload is required for signal %s, uid=%s"
                            .formatted(signalId, uid)
            );
        }
        if (payload.getPayloadBase() == null
                || !(payload.getPayloadBase().getValue() instanceof PayloadFloatType payloadFloat)) {
            throw complianceError(
                    "A numeric payloadFloat is required for signal %s, uid=%s"
                            .formatted(signalId, uid)
            );
        }

        return new ParsedInterval(
                uid,
                sequence,
                durationSeconds,
                decimal(payloadFloat.getValue())
        );
    }

    private BigDecimal decimal(float value) {
        return new BigDecimal(Float.toString(value));
    }

    private long parseDurationSeconds(String value, String subject) {
        try {
            return OpenAdrTimeUtils.parseOpenAdrDuration(value)
                    .map(Duration::getSeconds)
                    .orElseThrow(() -> complianceError("Duration is required for " + subject));
        } catch (EventValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw complianceError("Invalid duration for %s: %s".formatted(subject, value));
        }
    }

    private String itemBaseElement(EiEventSignalType signal, ItemBaseType itemBase) {
        if (itemBase instanceof PowerRealType) {
            return "powerReal";
        }
        return signal.getItemBase() != null
                ? signal.getItemBase().getName().getLocalPart()
                : null;
    }

    private String itemUnits(ItemBaseType itemBase) {
        if (itemBase instanceof PowerRealType powerReal) {
            return powerReal.getItemUnits();
        }
        if (itemBase instanceof CurrencyType currency && currency.getItemUnits() != null) {
            return currency.getItemUnits().value();
        }
        return null;
    }

    private String siScaleCode(ItemBaseType itemBase) {
        if (itemBase instanceof PowerRealType powerReal && powerReal.getSiScaleCode() != null) {
            return powerReal.getSiScaleCode().value();
        }
        if (itemBase instanceof CurrencyType currency && currency.getSiScaleCode() != null) {
            return currency.getSiScaleCode().value();
        }
        return null;
    }

    private EventValidationException complianceError(String message) {
        return new EventValidationException(
                message,
                com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER
        );
    }

    private EventValidationException invalidData(String message) {
        return new EventValidationException(
                message,
                com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.INVALID_DATA
        );
    }
}
