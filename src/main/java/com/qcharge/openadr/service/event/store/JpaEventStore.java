package com.qcharge.openadr.service.event.store;

import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.repository.DrEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Spring Data adapter. Protocol and execution services do not depend on JPA directly. */
@Component
@RequiredArgsConstructor
public class JpaEventStore implements EventStore {

    private final DrEventRepository repository;

    @Override
    public Optional<DrEvent> findByEventId(String eventId) {
        return repository.findByEventId(eventId);
    }

    @Override
    public List<DrEvent> findByExecutionStatusIn(Collection<DrEvent.ExecutionStatus> statuses) {
        return repository.findAllByExecutionStatusIn(statuses);
    }

    @Override
    public DrEvent save(DrEvent event) {
        return repository.save(event);
    }
}
