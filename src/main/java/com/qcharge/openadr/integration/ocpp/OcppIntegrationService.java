package com.qcharge.openadr.integration.ocpp;

import com.qcharge.openadr.service.event.execution.EventExecutionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Slf4j
@Service
public class OcppIntegrationService implements EventExecutionPort {

    /**
     * Executes one persisted interval. This is the openadr-service side of the future
     * qCharge-charging-profile-service contract.
     */
    @Override
    public void applyInterval(
            String eventId,
            int modificationNumber,
            String signalId,
            String intervalUid,
            String signalName,
            String signalType,
            BigDecimal value,
            String units,
            String siScaleCode,
            int intervalIndex,
            Instant effectiveFrom
    ) {
        switch (signalName) {
            case "LOAD_DISPATCH" -> log.info(
                    "TODO charging-profile-service: apply interval. eventId={}, modificationNumber={}, signalId={}, intervalUid={}, intervalIndex={}, effectiveFrom={}, type={}, value={}, units={}, scale={}",
                    eventId, modificationNumber, signalId, intervalUid, intervalIndex,
                    effectiveFrom, signalType, value, units, siScaleCode);
            case "SIMPLE" -> applySimple(eventId, value.intValueExact());
            case "ELECTRICITY_PRICE" -> log.info(
                    "TODO data-service policy: apply price interval. eventId={}, modificationNumber={}, signalId={}, intervalUid={}, intervalIndex={}, effectiveFrom={}, value={}, units={}, scale={}",
                    eventId, modificationNumber, signalId, intervalUid, intervalIndex,
                    effectiveFrom, value, units, siScaleCode);
            default -> throw new IllegalArgumentException("Unsupported persisted signal: " + signalName);
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
