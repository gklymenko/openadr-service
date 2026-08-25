package com.qcharge.openadr.service.event.protocol;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.ei.EiTargetType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.PayloadFloatType;
import com.qcharge.openadr.model.oadr20b.ei.SignalPayloadType;
import com.qcharge.openadr.model.oadr20b.emix.ItemBaseType;
import com.qcharge.openadr.model.oadr20b.oadr.CurrencyType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.power.PowerRealType;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.command.EventIntervalCommand;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.service.event.command.EventTargetCommand;
import com.qcharge.openadr.service.event.command.EventTimingCommand;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import com.qcharge.openadr.service.event.command.SignalTargetCommand;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Converts generated OpenADR/JAXB types into protocol-independent application commands. */
@Component
public class OpenAdrEventCommandMapper {

    public ReceiveEventCommand map(OadrEvent source) {
        var descriptor = requireDescriptor(source);
        String eventId = requireEventId(descriptor.getEventID());
        EventStatus status = status(descriptor.getEventStatus() != null
                ? descriptor.getEventStatus().value() : null);
        EventTimingCommand timing = timing(source);
        List<EventSignalCommand> signals = signals(source);

        return new ReceiveEventCommand(
                eventId,
                descriptor.getModificationNumber(),
                status,
                descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null,
                isTestEvent(descriptor.getTestEvent()),
                marketContext(descriptor),
                timing,
                signals,
                target(source.getEiEvent().getEiTarget())
        );
    }

    public String eventIdOf(OadrEvent source) {
        return source.getEiEvent() != null
                && source.getEiEvent().getEventDescriptor() != null
                ? source.getEiEvent().getEventDescriptor().getEventID()
                : null;
    }

    public long modificationNumberOf(OadrEvent source) {
        return source.getEiEvent() != null
                && source.getEiEvent().getEventDescriptor() != null
                ? source.getEiEvent().getEventDescriptor().getModificationNumber()
                : 0L;
    }

    private EventDescriptorType requireDescriptor(OadrEvent source) {
        if (source.getEiEvent() == null
                || source.getEiEvent().getEventDescriptor() == null) {
            throw complianceError("eventDescriptor is required");
        }

        return source.getEiEvent().getEventDescriptor();
    }

    private String requireEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            throw complianceError("eventID is required");
        }
        return eventId;
    }

    private EventStatus status(String value) {
        if (value == null || value.isBlank()) {
            throw invalidData("eventStatus is required");
        }
        try {
            return EventStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw invalidData("Unsupported eventStatus: " + value);
        }
    }

    private String marketContext(com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType descriptor) {
        return descriptor.getEiMarketContext() != null
                ? descriptor.getEiMarketContext().getMarketContext() : null;
    }

    private boolean isTestEvent(String value) {
        return value != null && !"false".equals(value);
    }

    private EventTimingCommand timing(OadrEvent source) {
        try {
            var properties = source.getEiEvent().getEiActivePeriod().getProperties();
            if (properties.getDtstart() == null || properties.getDtstart().getDateTime() == null) {
                throw complianceError("Event start time is required");
            }
            long startAfterSeconds = optionalDuration(
                    properties.getTolerance() != null
                            && properties.getTolerance().getTolerate() != null
                            ? properties.getTolerance().getTolerate().getStartafter() : null,
                    0L,
                    "event startafter"
            );
            if (startAfterSeconds < 0L) {
                throw invalidData("startafter must not be negative");
            }
            if (properties.getDuration() == null) {
                throw complianceError("Event duration is required");
            }
            long durationSeconds = requiredDuration(
                    properties.getDuration().getDuration(), "event");
            return new EventTimingCommand(
                    OpenAdrTimeUtils.fromXmlDateTime(properties.getDtstart().getDateTime()),
                    startAfterSeconds,
                    durationSeconds,
                    nullableDuration(properties.getXEiRampUp() != null
                            ? properties.getXEiRampUp().getDuration() : null, "event rampUp"),
                    nullableDuration(properties.getXEiRecovery() != null
                            ? properties.getXEiRecovery().getDuration() : null, "event recovery")
            );
        } catch (EventValidationException exception) {
            throw exception;
        } catch (NullPointerException exception) {
            throw complianceError("eiActivePeriod is required");
        } catch (RuntimeException exception) {
            throw complianceError("Invalid eiActivePeriod: " + exception.getMessage());
        }
    }

    private List<EventSignalCommand> signals(OadrEvent source) {
        if (source.getEiEvent().getEiEventSignals() == null
                || source.getEiEvent().getEiEventSignals().getEiEventSignal().isEmpty()) {
            return List.of();
        }
        return source.getEiEvent().getEiEventSignals().getEiEventSignal().stream()
                .map(this::signal)
                .toList();
    }

    private EventSignalCommand signal(EiEventSignalType source) {
        BigDecimal currentValue = source.getCurrentValue() != null
                && source.getCurrentValue().getPayloadFloat() != null
                ? decimal(source.getCurrentValue().getPayloadFloat().getValue()) : null;
        ItemBaseType itemBase = source.getItemBase() != null
                ? source.getItemBase().getValue() : null;
        List<IntervalType> sourceIntervals = source.getIntervals() != null
                ? source.getIntervals().getInterval() : List.of();
        if (sourceIntervals.isEmpty()) {
            throw complianceError("At least one interval is required for signal " + source.getSignalID());
        }

        List<EventIntervalCommand> intervals = new ArrayList<>();
        for (int sequence = 0; sequence < sourceIntervals.size(); sequence++) {
            intervals.add(interval(source.getSignalID(), sourceIntervals.get(sequence), sequence));
        }
        return new EventSignalCommand(
                source.getSignalID(),
                source.getSignalName(),
                source.getSignalType() != null ? source.getSignalType().value() : null,
                currentValue,
                itemBaseElement(source, itemBase),
                itemBase != null ? itemBase.getClass().getSimpleName() : null,
                itemUnits(itemBase),
                siScaleCode(itemBase),
                intervals,
                signalTarget(source.getEiTarget())
        );
    }

    private EventIntervalCommand interval(String signalId, IntervalType source, int sequence) {
        String uid = source.getUid() != null ? source.getUid().getText() : null;
        String duration = source.getDuration() != null ? source.getDuration().getDuration() : null;
        var payloads = source.getStreamPayloadBase();
        if (payloads.size() != 1
                || !(payloads.getFirst().getValue() instanceof SignalPayloadType signalPayload)) {
            throw complianceError("Exactly one signalPayload is required for signal %s, uid=%s"
                    .formatted(signalId, uid));
        }
        if (signalPayload.getPayloadBase() == null
                || !(signalPayload.getPayloadBase().getValue() instanceof PayloadFloatType payloadFloat)) {
            throw complianceError("A numeric payloadFloat is required for signal %s, uid=%s"
                    .formatted(signalId, uid));
        }
        return new EventIntervalCommand(
                uid,
                sequence,
                requiredDuration(duration, "interval for signal %s, uid=%s".formatted(signalId, uid)),
                decimal(payloadFloat.getValue()),
                source.getDtstart() != null
        );
    }

    private EventTargetCommand target(EiTargetType source) {
        if (source == null || !hasAnyTarget(source)) {
            return EventTargetCommand.absent();
        }
        return new EventTargetCommand(true, source.getVenID(), source.getResourceID());
    }

    private SignalTargetCommand signalTarget(EiTargetType source) {
        if (source == null) {
            return null;
        }
        return new SignalTargetCommand(
                hasNonDeviceClassTarget(source),
                source.getEndDeviceAsset().stream().map(asset -> asset.getMrid()).toList()
        );
    }

    private boolean hasAnyTarget(EiTargetType target) {
        return !target.getVenID().isEmpty() || !target.getResourceID().isEmpty()
                || !target.getGroupID().isEmpty() || !target.getGroupName().isEmpty()
                || !target.getPartyID().isEmpty() || !target.getAggregatedPnode().isEmpty()
                || !target.getEndDeviceAsset().isEmpty() || !target.getMeterAsset().isEmpty()
                || !target.getPnode().isEmpty() || !target.getServiceArea().isEmpty()
                || !target.getServiceDeliveryPoint().isEmpty() || !target.getServiceLocation().isEmpty()
                || !target.getTransportInterface().isEmpty();
    }

    private boolean hasNonDeviceClassTarget(EiTargetType target) {
        return !target.getVenID().isEmpty() || !target.getResourceID().isEmpty()
                || !target.getGroupID().isEmpty() || !target.getGroupName().isEmpty()
                || !target.getPartyID().isEmpty() || !target.getAggregatedPnode().isEmpty()
                || !target.getMeterAsset().isEmpty() || !target.getPnode().isEmpty()
                || !target.getServiceArea().isEmpty() || !target.getServiceDeliveryPoint().isEmpty()
                || !target.getServiceLocation().isEmpty() || !target.getTransportInterface().isEmpty();
    }

    private String itemBaseElement(EiEventSignalType signal, ItemBaseType itemBase) {
        if (itemBase instanceof PowerRealType) {
            return "powerReal";
        }
        return signal.getItemBase() != null
                ? signal.getItemBase().getName().getLocalPart() : null;
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

    private long requiredDuration(String value, String subject) {
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

    private Long nullableDuration(String value, String subject) {
        return value == null ? null : requiredDuration(value, subject);
    }

    private long optionalDuration(String value, long defaultValue, String subject) {
        return value == null ? defaultValue : requiredDuration(value, subject);
    }

    private BigDecimal decimal(float value) {
        return new BigDecimal(Float.toString(value));
    }

    private EventValidationException complianceError(String message) {
        return new EventValidationException(
                message, OpenADRResponseCode.COMPLIANCE_ERROR_OTHER);
    }

    private EventValidationException invalidData(String message) {
        return new EventValidationException(message, OpenADRResponseCode.INVALID_DATA);
    }
}
