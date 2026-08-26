package com.qcharge.openadr.service.report.telemetry;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.utility.TimeRange;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/** Thread-safe telemetry buffer retaining the configured window and at least 100 points. */
@Component
public class TelemetryBuffer {

    static final int MINIMUM_RETAINED_SAMPLES = 100;

    private final ConcurrentNavigableMap<Instant, TelemetrySample> samples =
            new ConcurrentSkipListMap<>();
    private final Duration retention;

    public TelemetryBuffer(OpenAdrProperties properties) {
        retention = Duration.ofSeconds(properties.getReport().getTelemetryRetentionSeconds());
    }

    public synchronized void add(TelemetrySample sample) {
        samples.put(sample.capturedAt(), sample);
        evictExpiredSamples();
    }

    public Optional<TelemetrySample> latest() {
        return samples.isEmpty()
                ? Optional.empty()
                : Optional.of(samples.lastEntry().getValue());
    }

    public Optional<TelemetrySample> latestAtOrBefore(Instant timestamp) {
        var entry = samples.floorEntry(timestamp);
        return entry == null ? Optional.empty() : Optional.of(entry.getValue());
    }

    public List<TelemetrySample> samplesIn(TimeRange range) {
        return List.copyOf(
                samples.subMap(range.start(), true, range.endExclusive(), false).values()
        );
    }

    private void evictExpiredSamples() {
        Instant retentionFloor = samples.lastKey().minus(retention);
        while (samples.size() > MINIMUM_RETAINED_SAMPLES) {
            var oldest = samples.firstEntry();
            if (!oldest.getKey().isBefore(retentionFloor)) {
                return;
            }
            samples.pollFirstEntry();
        }
    }
}
