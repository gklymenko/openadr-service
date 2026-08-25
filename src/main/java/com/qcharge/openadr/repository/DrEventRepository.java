package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.enums.event.EventExecutionStatus;
import com.qcharge.openadr.model.entity.DrEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DrEventRepository extends JpaRepository<DrEvent, Long> {
    Optional<DrEvent> findByEventId(String eventId);

    List<DrEvent> findAllByExecutionStatusIn(Collection<EventExecutionStatus> statuses);
}
