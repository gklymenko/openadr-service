package com.qcharge.openadr.integration.central.kafka;

import java.math.BigDecimal;
import java.time.Instant;

public record NormalizedMeterReading(
        Instant capturedAt,
        BigDecimal powerKw,
        BigDecimal energyRegisterKwh
) {
}
