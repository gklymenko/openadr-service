package com.qcharge.openadr.integration.ocpp;

import com.qcharge.openadr.service.event.execution.EventExecutionPort;
import com.qcharge.openadr.service.event.execution.EventIntervalExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class OcppIntegrationService implements EventExecutionPort {

    /**
     * Executes one persisted interval. This is the openadr-service side of the future
     * qCharge-charging-profile-service contract.
     */
    @Override
    public void applyInterval(EventIntervalExecution execution) {
        switch (execution.signalName()) {
            case "LOAD_DISPATCH" -> log.info(
                    "TODO charging-profile-service: apply interval. eventId={}, modificationNumber={}, signalId={}, intervalUid={}, intervalIndex={}, effectiveFrom={}, type={}, value={}, units={}, scale={}",
                    execution.eventId(), execution.modificationNumber(), execution.signalId(),
                    execution.intervalUid(), execution.intervalIndex(), execution.effectiveFrom(),
                    execution.signalType(), execution.value(), execution.units(), execution.siScaleCode());
            case "SIMPLE" -> applySimple(
                    execution.eventId(), execution.value().intValueExact());
            case "ELECTRICITY_PRICE" -> log.info(
                    "TODO data-service policy: apply price interval. eventId={}, modificationNumber={}, signalId={}, intervalUid={}, intervalIndex={}, effectiveFrom={}, value={}, units={}, scale={}",
                    execution.eventId(), execution.modificationNumber(), execution.signalId(),
                    execution.intervalUid(), execution.intervalIndex(), execution.effectiveFrom(),
                    execution.value(), execution.units(), execution.siScaleCode());
            default -> throw new IllegalArgumentException(
                    "Unsupported persisted signal: " + execution.signalName());
        }
    }

    private void applySimple(String eventId, int level) {
        // TODO Phase 9: map level to charging profile
        log.info("TODO OCPP: apply SIMPLE eventId={}, level={}", eventId, level);
    }

    @Override
    public void clearEvent(String eventId, ClearReason reason) {
        // TODO Phase 9: remove charging profile set for this event
        log.info("TODO OCPP: clear DR event eventId={}, reason={}", eventId, reason);
    }

}
