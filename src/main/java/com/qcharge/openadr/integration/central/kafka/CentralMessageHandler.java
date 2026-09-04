package com.qcharge.openadr.integration.central.kafka;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.MeterValues;
import com.qcharge.openadr.model.entity.ConnectorTelemetryState;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.entity.ResourceTelemetryStatus;
import com.qcharge.openadr.repository.ConnectorTelemetryStateRepository;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import com.qcharge.openadr.repository.ResourceTelemetryStatusRepository;
import com.qcharge.openadr.service.report.telemetry.ResourceTelemetrySnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CentralMessageHandler {

    private final OpenAdrResourceRepository resourceRepository;
    private final ConnectorTelemetryStateRepository connectorStateRepository;
    private final ResourceTelemetryStatusRepository statusRepository;
    private final CentralTelemetryMessageMapper messageMapper;
    private final ResourceTelemetrySnapshotService snapshotService;
    private final OpenAdrProperties properties;

    @Transactional
    public IngestionOutcome handleMeterValues(MeterValues message) {
        List<NormalizedMeterReading> readings = messageMapper.normalize(message).stream()
                .sorted(Comparator.comparing(NormalizedMeterReading::capturedAt))
                .toList();

        if (readings.isEmpty()) {
            throw new InvalidCentralMessageException("METER_VALUE contains no supported power or energy measurand");
        }

        Optional<OpenAdrResource> resource = lockResource(message.chargePointId());
        if (resource.isEmpty()) {
            return IngestionOutcome.UNKNOWN_RESOURCE;
        }

        OpenAdrResource lockedResource = resource.get();
        ConnectorTelemetryState state = connectorStateRepository
                .findByResource_IdAndConnectorNumber(
                        lockedResource.getId(), message.connectorId()
                )
                .orElseGet(() ->
                        newConnectorState(lockedResource, message.connectorId())
                );

        boolean applied = false;
        for (NormalizedMeterReading reading : readings) {
            boolean readingApplied = applyReading(state, message.transactionId(), reading);
            boolean statusApplied = updateLatestStatus(lockedResource, true, reading.capturedAt());
            if (readingApplied) {
                connectorStateRepository.save(state);
            }
            if (readingApplied || statusApplied) {
                snapshotService.capture(lockedResource, reading.capturedAt(), true);
                applied = true;
            }
        }
        return applied ? IngestionOutcome.APPLIED : IngestionOutcome.STALE_OR_DUPLICATE;
    }

    @Transactional
    public IngestionOutcome handleAvailability(String chargePointId, boolean online, Instant capturedAt) {
        if (chargePointId == null || chargePointId.isBlank()) {
            throw new InvalidCentralMessageException("Status message has no chargePointId");
        }
        if (capturedAt == null) {
            throw new InvalidCentralMessageException("Status message has no timestamp");
        }

        Optional<OpenAdrResource> resource = lockResource(chargePointId);
        if (resource.isEmpty()) {
            return IngestionOutcome.UNKNOWN_RESOURCE;
        }

        OpenAdrResource lockedResource = resource.get();
        if (!updateLatestStatus(lockedResource, online, capturedAt)) {
            return IngestionOutcome.STALE_OR_DUPLICATE;
        }

        snapshotService.capture(lockedResource, capturedAt, online);
        return IngestionOutcome.APPLIED;
    }

    private Optional<OpenAdrResource> lockResource(String chargePointId) {
        return resourceRepository.lockEnabledByChargePointIdentity(
                properties.getVen().getKey(),
                chargePointId.trim()
        );
    }

    private ConnectorTelemetryState newConnectorState(OpenAdrResource resource, int connectorNumber) {
        ConnectorTelemetryState state = new ConnectorTelemetryState();
        state.setResource(resource);
        state.setConnectorNumber(connectorNumber);
        return state;
    }

    private boolean applyReading(
            ConnectorTelemetryState state, Integer transactionId, NormalizedMeterReading reading
    ) {
        boolean applied = false;
        if (reading.powerKw() != null
                && isNewer(reading.capturedAt(), state.getPowerCapturedAt())) {
            state.setPowerKw(reading.powerKw());
            state.setPowerCapturedAt(reading.capturedAt());
            applied = true;
        }
        if (reading.energyRegisterKwh() != null
                && isNewer(reading.capturedAt(), state.getEnergyCapturedAt())) {
            state.setEnergyRegisterKwh(reading.energyRegisterKwh());
            state.setEnergyCapturedAt(reading.capturedAt());
            applied = true;
        }
        if (applied) {
            state.setTransactionId(transactionId);
        }
        return applied;
    }

    private boolean updateLatestStatus(
            OpenAdrResource resource, boolean online, Instant capturedAt
    ) {
        ResourceTelemetryStatus status = statusRepository.findByResource_Id(resource.getId())
                .orElseGet(() -> newStatus(resource));

        Instant currentTimestamp = status.getStatusCapturedAt();
        boolean newer = currentTimestamp == null || capturedAt.isAfter(currentTimestamp);
        boolean offlineWinsTie = capturedAt.equals(currentTimestamp)
                && status.isOnline()
                && !online;
        if (!newer && !offlineWinsTie) {
            return false;
        }

        status.setOnline(online);
        status.setStatusCapturedAt(capturedAt);
        statusRepository.save(status);
        return true;
    }

    private ResourceTelemetryStatus newStatus(OpenAdrResource resource) {
        ResourceTelemetryStatus status = new ResourceTelemetryStatus();
        status.setResource(resource);
        return status;
    }

    private boolean isNewer(Instant candidate, Instant current) {
        return current == null || candidate.isAfter(current);
    }
}
