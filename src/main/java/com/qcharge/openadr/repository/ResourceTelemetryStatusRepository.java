package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.ResourceTelemetryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResourceTelemetryStatusRepository
        extends JpaRepository<ResourceTelemetryStatus, Long> {

    Optional<ResourceTelemetryStatus> findByResource_Id(Long resourceId);
}
