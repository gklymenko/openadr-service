package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.OptSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OptScheduleRepository extends JpaRepository<OptSchedule, Long> {
    Optional<OptSchedule> findByOptId(String optId);

    Optional<OptSchedule> findByEventIdAndStatus(
            String eventId,
            OptSchedule.OptStatus status
    );
}