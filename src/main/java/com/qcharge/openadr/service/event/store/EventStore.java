package com.qcharge.openadr.service.event.store;

import com.qcharge.openadr.model.entity.DrEvent;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Application-facing persistence port for VEN events. */
public interface EventStore {

    Optional<DrEvent> findByEventId(String eventId);

    List<DrEvent> findByExecutionStatusIn(Collection<DrEvent.ExecutionStatus> statuses);

    DrEvent save(DrEvent event);
}
