package com.qcharge.openadr.service.report.telemetry;

import com.qcharge.openadr.model.entity.ResourceTelemetryStatus;
import com.qcharge.openadr.repository.ConnectorTelemetryStateRepository;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import com.qcharge.openadr.repository.ResourceTelemetryStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ResourceTelemetryMaterializationWorker {

    private final OpenAdrResourceRepository resourceRepository;
    private final ConnectorTelemetryStateRepository connectorStateRepository;
    private final ResourceTelemetryStatusRepository statusRepository;
    private final ResourceTelemetrySnapshotService snapshotService;

    @Transactional
    public void materialize(Long resourcePrimaryKey, Instant capturedAt) {
        resourceRepository.lockEnabledById(resourcePrimaryKey)
                .ifPresent(resource -> {
                    var status = statusRepository.findByResource_Id(resource.getId());
                    if (status.isEmpty()
                            && connectorStateRepository.findAllByResource_Id(resource.getId()).isEmpty()) {
                        return;
                    }
                    boolean online = status.map(ResourceTelemetryStatus::isOnline).orElse(false);
                    snapshotService.capture(resource, capturedAt, online);
                });
    }
}
