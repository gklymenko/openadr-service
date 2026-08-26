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
    void retainsAtLeastOneHundredSamplesAndSupportsHistoricalLookup() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getReport().setTelemetryRetentionSeconds(60);
        TelemetryBuffer buffer = new TelemetryBuffer(properties);
        Instant start = Instant.parse("2026-08-26T00:00:00Z");

        buffer.add(sample(start, 1.0f));
        buffer.add(sample(start.plusSeconds(30), 2.0f));
        for (int second = 1; second <= 100; second++) {
            buffer.add(sample(start.plusSeconds(second), second));
        }

        assertEquals(100, buffer.samplesIn(
                TimeRange.of(start, Duration.ofSeconds(101))
        ).size());
        assertEquals(
                45.0f,
                buffer.latestAtOrBefore(start.plusSeconds(45)).orElseThrow().powerKw()
        );
        assertTrue(buffer.latestAtOrBefore(start).isEmpty());
    }

    @Test
    void retainsAllSamplesInsideConfiguredTimeWindow() {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getReport().setTelemetryRetentionSeconds(300);
        TelemetryBuffer buffer = new TelemetryBuffer(properties);
        Instant start = Instant.parse("2026-08-26T00:00:00Z");

        for (int second = 0; second < 150; second++) {
            buffer.add(sample(start.plusSeconds(second), second));
        }

        assertEquals(150, buffer.samplesIn(
                TimeRange.of(start, Duration.ofSeconds(150))
        ).size());
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
