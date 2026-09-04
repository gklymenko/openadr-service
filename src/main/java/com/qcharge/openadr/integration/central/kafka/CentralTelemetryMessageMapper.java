package com.qcharge.openadr.integration.central.kafka;

import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.MeterValue;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.MeterValues;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.SampledValue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class CentralTelemetryMessageMapper {

    static final String POWER_ACTIVE_IMPORT = "Power.Active.Import";
    static final String ENERGY_ACTIVE_IMPORT_REGISTER = "Energy.Active.Import.Register";

    public List<NormalizedMeterReading> normalize(MeterValues message) {
        if (message == null || isBlank(message.chargePointId())) {
            throw new InvalidCentralMessageException("METER_VALUE has no chargePointId");
        }
        if (message.connectorId() == null || message.connectorId() < 0) {
            throw new InvalidCentralMessageException("METER_VALUE has an invalid connectorId");
        }
        if (message.meterValueList() == null || message.meterValueList().isEmpty()) {
            throw new InvalidCentralMessageException("METER_VALUE has no meterValueList");
        }

        return message.meterValueList().stream()
                .filter(Objects::nonNull)
                .map(this::normalize)
                .filter(reading -> reading.powerKw() != null || reading.energyRegisterKwh() != null)
                .toList();
    }

    public Instant parseTimestamp(String value, String field) {
        if (isBlank(value)) {
            throw new InvalidCentralMessageException(field + " is missing");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException exception) {
                throw new InvalidCentralMessageException(
                        field + " is not an ISO-8601 timestamp: " + value,
                        exception
                );
            }
        }
    }

    private NormalizedMeterReading normalize(MeterValue meterValue) {
        Instant capturedAt = parseTimestamp(meterValue.timestamp(), "meterValue.timestamp");
        List<SampledValue> values = meterValue.sampledValue() == null
                ? List.of()
                : meterValue.sampledValue();

        BigDecimal powerKw = selectOrSum(
                values,
                POWER_ACTIVE_IMPORT,
                this::powerToKw
        );
        BigDecimal energyKwh = selectOrSum(
                values,
                ENERGY_ACTIVE_IMPORT_REGISTER,
                this::energyToKwh
        );
        return new NormalizedMeterReading(capturedAt, powerKw, energyKwh);
    }

    private BigDecimal selectOrSum(
            List<SampledValue> values,
            String measurand,
            UnitNormalizer normalizer
    ) {
        List<SampledValue> matching = values.stream()
                .filter(Objects::nonNull)
                .filter(value -> measurand.equalsIgnoreCase(effectiveMeasurand(value)))
                .toList();

        for (SampledValue value : matching) {
            if (isBlank(value.phase())) {
                return normalizer.normalize(parseNumber(value), value.unit());
            }
        }

        Map<String, BigDecimal> byPhase = new LinkedHashMap<>();
        for (SampledValue value : matching) {
            if (!isBlank(value.phase())) {
                byPhase.put(
                        value.phase().trim().toUpperCase(Locale.ROOT),
                        normalizer.normalize(parseNumber(value), value.unit())
                );
            }
        }
        if (byPhase.isEmpty()) {
            return null;
        }
        return databaseValue(
                byPhase.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                measurand
        );
    }

    private BigDecimal parseNumber(SampledValue value) {
        if (isBlank(value.value())) {
            throw new InvalidCentralMessageException(
                    "Sampled value for " + value.measurand() + " is missing"
            );
        }
        try {
            return new BigDecimal(value.value().trim());
        } catch (NumberFormatException exception) {
            throw new InvalidCentralMessageException(
                    "Sampled value for " + value.measurand() + " is not numeric",
                    exception
            );
        }
    }

    private BigDecimal powerToKw(BigDecimal value, String unit) {
        String normalizedUnit = normalizedUnit(unit, "W");
        BigDecimal normalized = switch (normalizedUnit) {
            case "W" -> value.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            case "KW" -> value;
            default -> throw new InvalidCentralMessageException(
                    "Unsupported power unit: " + unit
            );
        };
        return databaseValue(normalized, POWER_ACTIVE_IMPORT);
    }

    private BigDecimal energyToKwh(BigDecimal value, String unit) {
        String normalizedUnit = normalizedUnit(unit, "WH");
        BigDecimal normalized = switch (normalizedUnit) {
            case "WH" -> value.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            case "KWH" -> value;
            default -> throw new InvalidCentralMessageException(
                    "Unsupported energy unit: " + unit
            );
        };
        return databaseValue(normalized, ENERGY_ACTIVE_IMPORT_REGISTER);
    }

    private BigDecimal databaseValue(BigDecimal value, String measurand) {
        BigDecimal scaled = value.setScale(6, RoundingMode.HALF_UP);
        if (scaled.signum() < 0 || scaled.precision() > 19) {
            throw new InvalidCentralMessageException(
                    "Out-of-range value for " + measurand
            );
        }
        return scaled;
    }

    private String normalizedUnit(String unit, String defaultUnit) {
        return isBlank(unit)
                ? defaultUnit
                : unit.trim().toUpperCase(Locale.ROOT);
    }

    private String effectiveMeasurand(SampledValue value) {
        // OCPP defaults an omitted measurand to Energy.Active.Import.Register.
        return isBlank(value.measurand())
                ? ENERGY_ACTIVE_IMPORT_REGISTER
                : value.measurand();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @FunctionalInterface
    private interface UnitNormalizer {
        BigDecimal normalize(BigDecimal value, String unit);
    }
}
