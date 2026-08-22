package com.qcharge.openadr.service.event.mapping;

import com.qcharge.openadr.model.entity.DrEventInterval;
import com.qcharge.openadr.model.entity.DrEventResource;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.service.event.command.EventIntervalCommand;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedResource;
import org.springframework.stereotype.Component;

/** Mechanical DTO-to-entity mappings. Aggregate wiring stays in {@link EventPayloadMapper}. */
@Component
public class EventEntityMapper {

    public DrEventSignal toSignal(EventSignalCommand source) {
        DrEventSignal target = new DrEventSignal();
        target.setSignalId(source.signalId());
        target.setSignalName(source.signalName());
        target.setSignalType(source.signalType());
        target.setCurrentValue(source.currentValue());
        target.setItemBaseElement(source.itemBaseElement());
        target.setItemBaseType(source.itemBaseType());
        target.setItemUnits(source.itemUnits());
        target.setSiScaleCode(source.siScaleCode());
        return target;
    }

    public DrEventInterval toInterval(EventIntervalCommand source) {
        DrEventInterval target = new DrEventInterval();
        target.setSequenceNumber(source.sequenceNumber());
        target.setIntervalUid(source.uid());
        target.setDurationSeconds(source.durationSeconds());
        target.setPayloadValue(source.payloadValue());
        return target;
    }

    public DrEventResource toResource(ResolvedResource source) {
        DrEventResource target = new DrEventResource();
        target.setResourceId(source.resourceId());
        return target;
    }
}
