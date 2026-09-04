package com.qcharge.openadr.service.report.telemetry;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.entity.ResourceTelemetrySample;
import com.qcharge.openadr.repository.ResourceTelemetrySampleRepository;
import com.qcharge.openadr.utility.TimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryBufferTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Mock
    private ResourceTelemetrySampleRepository repository;

    private OpenAdrProperties properties;
    private TelemetryBuffer buffer;
    private OpenAdrResource resource;

    @BeforeEach
    void setUp() {
        properties = new OpenAdrProperties();
        properties.getReport().setTelemetryRetentionSeconds(300);
        buffer = new TelemetryBuffer(
                repository,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        resource = new OpenAdrResource();
        resource.setId(7L);
        resource.setResourceId("charger-7");
    }

    @Test
    void storesAndReadsTelemetryByResource() {
        TelemetrySample sample = sample(NOW, 105.04f);
        when(repository.findByResource_IdAndCapturedAt(7L, NOW))
                .thenReturn(Optional.empty());
        when(repository.save(any(ResourceTelemetrySample.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TelemetrySample persisted = buffer.add(resource, sample);

        assertEquals(105.04f, persisted.powerKw());
        verify(repository).findByResource_IdAndCapturedAt(7L, NOW);
        verify(repository).save(any(ResourceTelemetrySample.class));

        TimeRange range = TimeRange.of(NOW.minusSeconds(60), Duration.ofSeconds(60));
        when(repository
                .findByResource_ResourceIdAndCapturedAtGreaterThanEqualAndCapturedAtLessThanOrderByCapturedAtAsc(
                        "charger-7",
                        range.start(),
                        range.endExclusive()
                ))
                .thenReturn(List.of(entity(NOW.minusSeconds(1), 42.0f)));

        assertEquals(42.0f, buffer.samplesIn("charger-7", range).getFirst().powerKw());
    }

    @Test
    void cleanupPreservesNewestOneHundredSamplesPerResource() {
        List<ResourceTelemetrySample> newest = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            newest.add(entity(NOW.minusSeconds(index), index));
        }
        when(repository.findDistinctResourceIds()).thenReturn(List.of(7L));
        when(repository.findTop100ByResource_IdOrderByCapturedAtDesc(7L))
                .thenReturn(newest);

        buffer.removeExpiredSamples();

        // Retention cutoff is older than the oldest protected point, so recent
        // samples in the configured time window are retained as well.
        verify(repository).deleteOlderThan(7L, NOW.minusSeconds(300));
    }

    private ResourceTelemetrySample entity(Instant capturedAt, float powerKw) {
        ResourceTelemetrySample entity = new ResourceTelemetrySample();
        entity.setResource(resource);
        entity.setCapturedAt(capturedAt);
        entity.setPowerKw(BigDecimal.valueOf(powerKw));
        entity.setEnergyKwh(BigDecimal.ZERO);
        entity.setOnline(true);
        return entity;
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
