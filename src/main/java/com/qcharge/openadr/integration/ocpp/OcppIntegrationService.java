package com.qcharge.openadr.integration.ocpp;

import com.qcharge.openadr.service.event.EventValidationService.ParsedSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class OcppIntegrationService {

    public void applySignal(String eventId, ParsedSignal signal) {
        switch (signal.signalName()) {
            case "LOAD_DISPATCH" -> applyLoadDispatch(
                    eventId,
                    signal.currentValue() != null ? signal.currentValue() : BigDecimal.ZERO);
            case "SIMPLE" -> applySimple(
                    eventId,
                    signal.currentValue() != null ? signal.currentValue().intValue() : 0);
            case "ELECTRICITY_PRICE" ->
                    log.info("TODO OCPP: ELECTRICITY_PRICE signal eventId={}, value={} {}/kWh",
                            eventId, signal.currentValue(), signal.signalType());
            default -> log.warn("TODO OCPP: unhandled signal name={} eventId={}", signal.signalName(), eventId);
        }
    }

    public void applyLoadDispatch(String eventId, BigDecimal kWLimit) {
        // TODO Phase 9: call smart-charging-service via Feign
        log.info("TODO OCPP: apply LOAD_DISPATCH eventId={}, kWLimit={}kW", eventId, kWLimit);
    }

    public void applySimple(String eventId, int level) {
        // TODO Phase 9: map level to charging profile
        log.info("TODO OCPP: apply SIMPLE eventId={}, level={}", eventId, level);
    }

    public void clearEvent(String eventId) {
        // TODO Phase 9: remove charging profile set for this event
        log.info("TODO OCPP: clear DR event eventId={}", eventId);
    }
}
