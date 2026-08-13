package com.qcharge.openadr.service.event.mapping;

import com.qcharge.openadr.model.entity.DrEventInterval;
import com.qcharge.openadr.model.entity.DrEventResource;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.service.event.command.EventIntervalCommand;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.resource.EventResourceResolver.ResolvedResource;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

/** Mechanical DTO-to-entity mappings. Aggregate wiring stays in {@link EventPayloadMapper}. */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface EventEntityMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "event", ignore = true)
    @Mapping(target = "sequenceNumber", ignore = true)
    @Mapping(target = "selectedForExecution", ignore = true)
    @Mapping(target = "intervals", ignore = true)
    DrEventSignal toSignal(EventSignalCommand source);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "signal", ignore = true)
    @Mapping(target = "intervalUid", source = "uid")
    DrEventInterval toInterval(EventIntervalCommand source);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "event", ignore = true)
    @Mapping(target = "sequenceNumber", ignore = true)
    DrEventResource toResource(ResolvedResource source);
}
