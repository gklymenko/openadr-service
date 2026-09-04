package com.qcharge.openadr.service.report.telemetry;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.ConnectorTelemetryState;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.repository.ConnectorTelemetryStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/** Builds and persists one charger/resource snapshot from normalized connector state. */
@Service
@RequiredArgsConstructor
public class ResourceTelemetrySnapshotService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ConnectorTelemetryStateRepository connectorStateRepository;
    private final TelemetryBuffer telemetryBuffer;
    private final OpenAdrProperties properties;

    @Transactional
    public TelemetrySample capture(
            OpenAdrResource resource,
            Instant capturedAt,
            boolean onlineAtCapturedAt
    ) {
        List<ConnectorTelemetryState> connectorStates =
                connectorStateRepository.findAllByResource_Id(resource.getId());

        Instant freshnessFloor = capturedAt.minusSeconds(
                properties.getReport().getTelemetryStaleAfterSeconds()
        );
        BigDecimal powerKw = aggregate(
                connectorStates.stream()
                        .filter(state -> state.getPowerKw() != null)
                        .filter(state -> isFresh(state.getPowerCapturedAt(), freshnessFloor, capturedAt))
                        .toList(),
                ConnectorTelemetryState::getPowerKw
        );
        BigDecimal energyKwh = aggregate(
                connectorStates.stream()
                        .filter(state -> state.getEnergyRegisterKwh() != null)
                        .filter(state -> state.getEnergyCapturedAt() != null)
                        .filter(state -> !state.getEnergyCapturedAt().isAfter(capturedAt))
                        .toList(),
                ConnectorTelemetryState::getEnergyRegisterKwh
        );

        return telemetryBuffer.add(
                resource,
                capturedAt,
                onlineAtCapturedAt ? powerKw : ZERO,
                energyKwh,
                onlineAtCapturedAt
        );
    }

    private boolean isFresh(Instant valueTimestamp, Instant freshnessFloor, Instant capturedAt) {
        return valueTimestamp != null
                && !valueTimestamp.isBefore(freshnessFloor)
                && !valueTimestamp.isAfter(capturedAt);
    }

    /**
     * OCPP connector 0 is the charge-point total. If it is absent, aggregate the
     * physical connectors and deliberately exclude connector 0 to avoid double counting.
     */
    private BigDecimal aggregate(
            List<ConnectorTelemetryState> states,
            Function<ConnectorTelemetryState, BigDecimal> value
    ) {
        return states.stream()
                .filter(state -> state.getConnectorNumber() == 0)
                .findFirst()
                .map(value)
                .orElseGet(() -> states.stream()
                        .filter(state -> state.getConnectorNumber() > 0)
                        .map(value)
                        .reduce(ZERO, BigDecimal::add));
    }
}
