package com.qcharge.openadr.integration.central.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.ConnectorStatus;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.CentralMessage;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.Heartbeat;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.MeterValues;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.ResourceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class CentralKafkaMessageDispatcher {

    private final ObjectMapper objectMapper;
    private final CentralTelemetryIngestionService ingestionService;
    private final CentralTelemetryMessageMapper telemetryMapper;

    public void dispatch(String payload, Instant kafkaTimestamp) {
        CentralMessage centralMessage = read(payload, CentralMessage.class);
        if (centralMessage.messageType() == null) {
            throw new InvalidCentralMessageException("Central message has no messageType");
        }

        IngestionOutcome outcome = switch (centralMessage.messageType()) {
            case METER_VALUE -> meterValues(payload);
            case CONNECTOR_STATUS -> connectorStatus(payload, kafkaTimestamp);
            case HEARTBEAT -> heartbeat(payload, kafkaTimestamp);
            case PONG -> resourceStatus(payload, true, kafkaTimestamp);
            case DISCONNECTED -> resourceStatus(payload, false, kafkaTimestamp);

            default -> null;
        };

        if (outcome == IngestionOutcome.UNKNOWN_RESOURCE) {
            log.debug("Ignoring telemetry for a charger that is not enabled OpenADR resource");
        }
    }

    private IngestionOutcome meterValues(String payload) {
        return ingestionService.ingestMeterValues(read(payload, MeterValues.class));
    }

    private IngestionOutcome connectorStatus(String payload, Instant kafkaTimestamp) {
        ConnectorStatus message = read(payload, ConnectorStatus.class);
        return ingestionService.ingestAvailability(
                message.chargePointId(),
                true,
                timestampOrFallback(message.timestamp(), "timestamp", kafkaTimestamp)
        );
    }

    private IngestionOutcome heartbeat(String payload, Instant kafkaTimestamp) {
        Heartbeat message = read(payload, Heartbeat.class);
        return ingestionService.ingestAvailability(
                message.chargePointId(),
                true,
                timestampOrFallback(
                        message.lastHeartbeatTimestamp(),
                        "lastHeartbeatTimestamp",
                        kafkaTimestamp
                )
        );
    }

    private IngestionOutcome resourceStatus(
            String payload,
            boolean online,
            Instant kafkaTimestamp
    ) {
        ResourceStatus message = read(payload, ResourceStatus.class);
        return ingestionService.ingestAvailability(
                message.chargePointId(),
                online,
                timestampOrFallback(message.timestamp(), "timestamp", kafkaTimestamp)
        );
    }

    private Instant timestampOrFallback(
            String timestamp, String field, Instant kafkaTimestamp
    ) {
        return timestamp == null || timestamp.isBlank()
                ? kafkaTimestamp
                : telemetryMapper.parseTimestamp(timestamp, field);
    }

    private <T> T read(String payload, Class<T> type) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidCentralMessageException("Central message is empty");
        }
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException exception) {
            throw new InvalidCentralMessageException("Invalid central-service JSON", exception);
        }
    }
}
