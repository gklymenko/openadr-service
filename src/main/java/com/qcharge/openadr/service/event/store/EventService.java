package com.qcharge.openadr.service.event.store;

import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.repository.DrEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class EventService {

    private final DrEventRepository repository;

    public Optional<DrEvent> findByEventId(String eventId) {
        return repository.findByEventId(eventId);
    }

    public List<DrEvent> findByExecutionStatusIn(Collection<DrEvent.ExecutionStatus> statuses) {
        return repository.findAllByExecutionStatusIn(statuses);
    }

    public DrEvent save(DrEvent event) {
        return repository.save(event);
    }
}
