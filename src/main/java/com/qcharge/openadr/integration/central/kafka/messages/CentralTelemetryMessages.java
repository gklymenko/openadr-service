package com.qcharge.openadr.integration.central.kafka.messages;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public final class CentralTelemetryMessages {

    private CentralTelemetryMessages() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CentralMessage(CentralMessageType messageType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeterValues(
            String messageType,
            String chargePointId,
            Integer connectorId,
            Integer transactionId,
            List<MeterValue> meterValueList
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeterValue(
            String timestamp,
            List<SampledValue> sampledValue
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SampledValue(
            String value,
            String context,
            String format,
            String measurand,
            String phase,
            String location,
            String unit
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConnectorStatus(
            String messageType,
            String chargePointId,
            Integer connectorId,
            String connectorStatus,
            String timestamp
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Heartbeat(
            String messageType,
            String chargePointId,
            String lastHeartbeatTimestamp
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceStatus(
            String messageType,
            String chargePointId,
            String timestamp
    ) {
    }
}
