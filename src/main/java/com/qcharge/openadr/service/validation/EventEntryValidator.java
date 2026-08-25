package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.ei.EiEventType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.PayloadFloatType;
import com.qcharge.openadr.model.oadr20b.ei.SignalPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.hasText;

/**
 * Validates one oadrEvent independently so a malformed entry does not reject valid siblings.
 * Required-field checks temporarily cover the disabled XSD validation; OpenADR semantic rules
 * remain necessary after schema validation is enabled.
 */
@Component
public class EventEntryValidator {

    public void validate(OadrEvent source) {
        if (source == null) {
            throw complianceError("oadrEvent is required");
        }
        if (source.getOadrResponseRequired() == null) {
            throw complianceError("oadrResponseRequired is required");
        }

        EiEventType event = source.getEiEvent();
        if (event == null) {
            throw complianceError("eiEvent is required");
        }

        EventDescriptorType descriptor = event.getEventDescriptor();
        validateDescriptor(descriptor);
        long eventDurationSeconds = validateActivePeriod(event);
        supportedSignals(event, descriptor.getEventStatus().value(), eventDurationSeconds);

        if (event.getEiTarget() == null) {
            throw complianceError("eiTarget is required");
        }
    }

    private void validateDescriptor(EventDescriptorType descriptor) {
        if (descriptor == null) {
            throw complianceError("eventDescriptor is required");
        }
        if (!hasText(descriptor.getEventID())) {
            throw complianceError("eventID is required");
        }
        if (descriptor.getModificationNumber() < 0L) {
            throw complianceError("modificationNumber must not be negative");
        }
        if (descriptor.getEventStatus() == null) {
            throw invalidData("eventStatus is required");
        }
        try {
            EventStatus.valueOf(descriptor.getEventStatus().value().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw invalidData("Unsupported eventStatus: " + descriptor.getEventStatus().value());
        }
        if (descriptor.getEiMarketContext() == null
                || !hasText(descriptor.getEiMarketContext().getMarketContext())) {
            throw complianceError("marketContext is required");
        }
        if (descriptor.getCreatedDateTime() == null) {
            throw complianceError("createdDateTime is required");
        }
    }

    private long validateActivePeriod(EiEventType event) {
        // Rule 105: every event carries an active period.
        if (event.getEiActivePeriod() == null
                || event.getEiActivePeriod().getProperties() == null) {
            throw complianceError("eiActivePeriod is required");
        }

        var properties = event.getEiActivePeriod().getProperties();
        if (properties.getDtstart() == null
                || properties.getDtstart().getDateTime() == null) {
            throw complianceError("Event start time is required");
        }
        try {
            OpenAdrTimeUtils.fromXmlDateTime(properties.getDtstart().getDateTime());
        } catch (RuntimeException exception) {
            throw complianceError("Invalid event start time");
        }

        if (properties.getDuration() == null) {
            throw complianceError("Event duration is required");
        }
        long durationSeconds = validateDuration(properties.getDuration().getDuration(), "event");
        if (durationSeconds < 0L) {
            throw invalidData("Event duration must not be negative");
        }

        String startAfter = properties.getTolerance() != null
                && properties.getTolerance().getTolerate() != null
                ? properties.getTolerance().getTolerate().getStartafter()
                : null;
        if (startAfter != null && validateDuration(startAfter, "event startafter") < 0L) {
            throw invalidData("startafter must not be negative");
        }
        if (properties.getXEiRampUp() != null
                && validateDuration(properties.getXEiRampUp().getDuration(), "event rampUp") < 0L) {
            throw invalidData("rampUp must not be negative");
        }
        if (properties.getXEiRecovery() != null
                && validateDuration(properties.getXEiRecovery().getDuration(), "event recovery") < 0L) {
            throw invalidData("recovery must not be negative");
        }
        return durationSeconds;
    }

    private void supportedSignals(
            EiEventType event,
            String eventStatus,
            long eventDurationSeconds
    ) {
        if (event.getEiEventSignals() == null
                || event.getEiEventSignals().getEiEventSignal().isEmpty()) {
            throw complianceError("At least one eiEventSignal is required");
        }

        // Rule 107: signalID is unique within an event.
        Set<String> signalIds = new HashSet<>();
        for (EiEventSignalType signal : event.getEiEventSignals().getEiEventSignal()) {
            validateSignal(signal, eventStatus, eventDurationSeconds, signalIds);
        }
    }

    private void validateSignal(
            EiEventSignalType signal,
            String eventStatus,
            long eventDurationSeconds,
            Set<String> signalIds
    ) {
        if (signal == null) {
            throw complianceError("eiEventSignal is required");
        }
        if (!hasText(signal.getSignalID())) {
            throw complianceError("signalID is required");
        }
        if (!signalIds.add(signal.getSignalID())) {
            throw complianceError("signalID must be unique within an event: " + signal.getSignalID());
        }
        if (!hasText(signal.getSignalName())) {
            throw complianceError("signalName is required for signal " + signal.getSignalID());
        }
        if (signal.getSignalType() == null) {
            throw complianceError("signalType is required for signal " + signal.getSignalID());
        }
        if (signal.getIntervals() == null
                || signal.getIntervals().getInterval().isEmpty()) {
            throw complianceError("At least one interval is required for signal " + signal.getSignalID());
        }

        boolean openEnded = eventDurationSeconds == 0L;
        long intervalsDuration = 0L;
        var intervals = signal.getIntervals().getInterval();
        for (int sequence = 0; sequence < intervals.size(); sequence++) {
            long duration = validateInterval(signal, intervals.get(sequence), sequence, openEnded);
            try {
                intervalsDuration = Math.addExact(intervalsDuration, duration);
            } catch (ArithmeticException exception) {
                throw invalidData("Interval durations overflow for signal " + signal.getSignalID());
            }
        }

        // Rules 8 and 102: finite-event interval durations equal the active-period duration.
        if (!openEnded && intervalsDuration != eventDurationSeconds) {
            throw complianceError(
                    "interval durations for signal %s sum to %d seconds; event duration is %d seconds"
                            .formatted(signal.getSignalID(), intervalsDuration, eventDurationSeconds)
            );
        }

        validateCurrentValue(signal, eventStatus);
    }

    private long validateInterval(
            EiEventSignalType signal,
            IntervalType interval,
            int sequence,
            boolean openEnded
    ) {
        if (interval == null) {
            throw complianceError("Interval is required for signal " + signal.getSignalID());
        }

        // Rule 103: signal intervals are contiguous and therefore do not carry dtstart.
        if (interval.getDtstart() != null) {
            throw complianceError("Interval dtstart is not allowed for signal " + signal.getSignalID());
        }

        String uid = interval.getUid() != null ? interval.getUid().getText() : null;
        // Rule 2: interval uid starts at 0 and increments by 1.
        String expectedUid = Integer.toString(sequence);
        if (!expectedUid.equals(uid)) {
            throw complianceError(
                    "Interval uid for signal %s must be %s but was %s"
                            .formatted(signal.getSignalID(), expectedUid, uid)
            );
        }
        if (interval.getDuration() == null) {
            throw complianceError(
                    "Duration is required for interval for signal %s, uid=%s"
                            .formatted(signal.getSignalID(), uid)
            );
        }
        long duration = validateDuration(
                interval.getDuration().getDuration(),
                "interval for signal %s, uid=%s".formatted(signal.getSignalID(), uid)
        );
        if (duration < 0L || (duration == 0L && !openEnded)) {
            throw complianceError(
                    "Interval duration must be positive unless the event is open-ended "
                            + "for signal %s, uid=%s".formatted(signal.getSignalID(), uid)
            );
        }

        // Rule 100: exactly one signalPayload containing payloadFloat is present.
        var payloads = interval.getStreamPayloadBase();
        if (payloads.size() != 1
                || payloads.getFirst() == null
                || !(payloads.getFirst().getValue() instanceof SignalPayloadType signalPayload)
                || signalPayload.getPayloadBase() == null
                || !(signalPayload.getPayloadBase().getValue() instanceof PayloadFloatType payload)) {
            throw complianceError(
                    "Exactly one numeric signalPayload is required for signal %s, uid=%s"
                            .formatted(signal.getSignalID(), uid)
            );
        }

        // Rule 9: SIMPLE signal payload values are the integer levels 0, 1, 2 or 3.
        if ("SIMPLE".equalsIgnoreCase(signal.getSignalName())) {
            validateSimpleLevel(payload.getValue(), "SIMPLE interval uid=" + uid);
        } else if (!Float.isFinite(payload.getValue())) {
            throw invalidData("Signal payload must be finite for signal " + signal.getSignalID());
        }
        return duration;
    }

    private void validateCurrentValue(EiEventSignalType signal, String eventStatus) {
        if (signal.getCurrentValue() == null) {
            return;
        }
        if (signal.getCurrentValue().getPayloadFloat() == null) {
            throw complianceError("currentValue payloadFloat is required for signal " + signal.getSignalID());
        }
        if (!"SIMPLE".equalsIgnoreCase(signal.getSignalName())) {
            return;
        }

        float value = signal.getCurrentValue().getPayloadFloat().getValue();
        if (!Float.isFinite(value)) {
            throw invalidData("currentValue must be finite for signal " + signal.getSignalID());
        }
        validateSimpleLevel(value, "SIMPLE currentValue");
        // Rule 14: a non-active SIMPLE event reports currentValue 0.
        if (!"active".equalsIgnoreCase(eventStatus) && Float.compare(value, 0.0f) != 0) {
            throw invalidData("SIMPLE currentValue must be 0 while event is not active");
        }
    }

    private void validateSimpleLevel(float value, String subject) {
        if (!Float.isFinite(value)) {
            throw invalidData(subject + " must be one of 0, 1, 2, 3");
        }
        BigDecimal decimal = new BigDecimal(Float.toString(value));
        if (decimal.stripTrailingZeros().scale() > 0
                || decimal.compareTo(BigDecimal.ZERO) < 0
                || decimal.compareTo(BigDecimal.valueOf(3)) > 0) {
            throw invalidData(subject + " must be one of 0, 1, 2, 3");
        }
    }

    private long validateDuration(String value, String subject) {
        try {
            return OpenAdrTimeUtils.parseOpenAdrDuration(value)
                    .orElseThrow(() -> complianceError("Duration is required for " + subject))
                    .getSeconds();
        } catch (EventValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw complianceError("Invalid duration for %s: %s".formatted(subject, value));
        }
    }

    private EventValidationException complianceError(String message) {
        return new EventValidationException(message, OpenADRResponseCode.COMPLIANCE_ERROR_OTHER);
    }

    private EventValidationException invalidData(String message) {
        return new EventValidationException(message, OpenADRResponseCode.INVALID_DATA);
    }
}
