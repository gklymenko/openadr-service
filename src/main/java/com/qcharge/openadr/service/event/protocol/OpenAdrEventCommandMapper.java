package com.qcharge.openadr.service.event.protocol;

import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.model.oadr20b.ei.EiEventType;
import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.ei.EiTargetType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.PayloadFloatType;
import com.qcharge.openadr.model.oadr20b.ei.SignalPayloadType;
import com.qcharge.openadr.model.oadr20b.emix.ItemBaseType;
import com.qcharge.openadr.model.oadr20b.oadr.CurrencyType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.model.oadr20b.power.EndDeviceAssetType;
import com.qcharge.openadr.model.oadr20b.power.PowerRealType;
import com.qcharge.openadr.service.event.command.EventIntervalCommand;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.event.command.EventTargetCommand;
import com.qcharge.openadr.service.event.command.EventTimingCommand;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import com.qcharge.openadr.service.event.command.SignalTargetCommand;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Converts generated OpenADR/JAXB types into protocol-independent application commands. */
@Component
public class OpenAdrEventCommandMapper {

    public ReceiveEventCommand map(@NotNull OadrEvent source) {
        EiEventType event = source.getEiEvent();
        EventDescriptorType descriptor = event.getEventDescriptor();
        EventStatus status = EventStatus.valueOf(
                descriptor.getEventStatus().value().toUpperCase()
        );
        EventTimingCommand timing = timing(event);
        List<EventSignalCommand> signals = signals(event);

        return new ReceiveEventCommand(
                descriptor.getEventID(),
                descriptor.getModificationNumber(),
                status,
                descriptor.getPriority() != null ? descriptor.getPriority().intValue() : null,
                isTestEvent(descriptor.getTestEvent()),
                marketContext(descriptor),
                timing,
                signals,
                target(event.getEiTarget())
        );
    }

    private String marketContext(EventDescriptorType descriptor) {
        return descriptor.getEiMarketContext() != null
                ? descriptor.getEiMarketContext().getMarketContext() : null;
    }

    private boolean isTestEvent(String value) {
        return value != null && !"false".equals(value);
    }

    private EventTimingCommand timing(EiEventType source) {
        var properties = source.getEiActivePeriod().getProperties();
        long startAfterSeconds = optionalDuration(
                properties.getTolerance() != null
                        && properties.getTolerance().getTolerate() != null
                        ? properties.getTolerance().getTolerate().getStartafter() : null,
                0L
        );
        return new EventTimingCommand(
                OpenAdrTimeUtils.fromXmlDateTime(properties.getDtstart().getDateTime()),
                startAfterSeconds,
                durationSeconds(properties.getDuration().getDuration()),
                nullableDuration(properties.getXEiRampUp() != null
                        ? properties.getXEiRampUp().getDuration() : null),
                nullableDuration(properties.getXEiRecovery() != null
                        ? properties.getXEiRecovery().getDuration() : null)
        );
    }

    private List<EventSignalCommand> signals(EiEventType source) {
        if (source.getEiEventSignals() == null
                || source.getEiEventSignals().getEiEventSignal().isEmpty()) {
            return List.of();
        }
        return source.getEiEventSignals().getEiEventSignal().stream()
                .map(this::signal)
                .toList();
    }

    private EventSignalCommand signal(EiEventSignalType source) {
        BigDecimal currentValue = source.getCurrentValue() != null
                && source.getCurrentValue().getPayloadFloat() != null
                ? decimal(source.getCurrentValue().getPayloadFloat().getValue()) : null;
        ItemBaseType itemBase = source.getItemBase() != null
                ? source.getItemBase().getValue() : null;
        List<IntervalType> sourceIntervals = source.getIntervals().getInterval();

        List<EventIntervalCommand> intervals = new ArrayList<>();
        for (int sequence = 0; sequence < sourceIntervals.size(); sequence++) {
            intervals.add(interval(sourceIntervals.get(sequence), sequence));
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

    private EventIntervalCommand interval(IntervalType source, int sequence) {
        String uid = source.getUid() != null ? source.getUid().getText() : null;
        SignalPayloadType signalPayload = (SignalPayloadType)
                source.getStreamPayloadBase().getFirst().getValue();
        PayloadFloatType payloadFloat = (PayloadFloatType)
                signalPayload.getPayloadBase().getValue();
        return new EventIntervalCommand(
                uid,
                sequence,
                durationSeconds(source.getDuration().getDuration()),
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
                source.getEndDeviceAsset().stream().map(EndDeviceAssetType::getMrid).toList()
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

    private long durationSeconds(String value) {
        return OpenAdrTimeUtils.parseOpenAdrDuration(value)
                .orElseThrow(() -> new IllegalStateException(
                        "Event duration must be validated before mapping"
                ))
                .getSeconds();
    }

    private Long nullableDuration(String value) {
        return value == null ? null : durationSeconds(value);
    }

    private long optionalDuration(String value, long defaultValue) {
        return value == null ? defaultValue : durationSeconds(value);
    }

    private BigDecimal decimal(float value) {
        return new BigDecimal(Float.toString(value));
    }

}
