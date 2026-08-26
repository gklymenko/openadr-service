package com.qcharge.openadr.service.report.telemetry;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.utility.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryBufferTest {

    @Test
    void evictsSamplesOutsideRetentionAndSupportsHistoricalLookup() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getReport().setTelemetryRetentionSeconds(60);
        TelemetryBuffer buffer = new TelemetryBuffer(properties);
        Instant start = Instant.parse("2026-08-26T00:00:00Z");

        buffer.add(sample(start, 1.0f));
        buffer.add(sample(start.plusSeconds(30), 2.0f));
        buffer.add(sample(start.plusSeconds(61), 3.0f));

        assertEquals(2, buffer.samplesIn(
                TimeRange.of(start.plusSeconds(1), Duration.ofSeconds(61))
        ).size());
        assertEquals(
                2.0f,
                buffer.latestAtOrBefore(start.plusSeconds(45)).orElseThrow().powerKw()
        );
        assertTrue(buffer.latestAtOrBefore(start).isEmpty());
    }

    private TelemetrySample sample(Instant timestamp, float powerKw) {
        return new TelemetrySample(
                timestamp,
                powerKw,
                0.0f,
                true,
                false,
                1.0f,
                1.0f,
                0.0f,
                1.0f
        );
    }
}
