package com.qcharge.openadr.integration.central.kafka;

import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.MeterValue;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.MeterValues;
import com.qcharge.openadr.integration.central.kafka.messages.CentralTelemetryMessages.SampledValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CentralTelemetryMessageMapperTest {

    private final CentralTelemetryMessageMapper mapper =
            new CentralTelemetryMessageMapper();

    @Test
    void convertsOcppPowerAndEnergyToOpenAdrUnits() {
        MeterValues message = new MeterValues(
                "METER_VALUE",
                "DC890000",
                2,
                3666,
                List.of(new MeterValue(
                        "2026-09-03T05:08:29.729Z",
                        List.of(
                                sampled("105040.48", "Power.Active.Import", null, "W"),
                                sampled("6926.94", "Energy.Active.Import.Register", null, "Wh"),
                                sampled("67.00", "SoC", null, "Percent")
                        )
                ))
        );

        NormalizedMeterReading result = mapper.normalize(message).getFirst();

        assertEquals(Instant.parse("2026-09-03T05:08:29.729Z"), result.capturedAt());
        assertEquals(new BigDecimal("105.040480"), result.powerKw());
        assertEquals(new BigDecimal("6.926940"), result.energyRegisterKwh());
    }

    @Test
    void sumsPhasesWhenNoTotalMeasurementIsPresent() {
        MeterValues message = new MeterValues(
                "METER_VALUE",
                "AC100",
                1,
                null,
                List.of(new MeterValue(
                        "2026-09-03T05:08:29Z",
                        List.of(
                                sampled("1000", "Power.Active.Import", "L1", "W"),
                                sampled("1500", "Power.Active.Import", "L2", "W"),
                                sampled("500", "Power.Active.Import", "L3", "W")
                        )
                ))
        );

        assertEquals(
                new BigDecimal("3.000000"),
                mapper.normalize(message).getFirst().powerKw()
        );
    }

    private SampledValue sampled(
            String value,
            String measurand,
            String phase,
            String unit
    ) {
        return new SampledValue(
                value,
                "Sample.Periodic",
                "Raw",
                measurand,
                phase,
                "Outlet",
                unit
        );
    }
}
