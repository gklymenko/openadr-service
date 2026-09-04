package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.ConnectorTelemetryState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectorTelemetryStateRepository extends JpaRepository<ConnectorTelemetryState, Long> {

    Optional<ConnectorTelemetryState> findByResource_IdAndConnectorNumber(Long resourceId, int connectorNumber);

    List<ConnectorTelemetryState> findAllByResource_Id(Long resourceId);
}
