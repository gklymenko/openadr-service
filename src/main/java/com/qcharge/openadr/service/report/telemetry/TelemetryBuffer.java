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

/** Thread-safe, time-bounded telemetry buffer used by one-shot and periodic reports. */
@Component
public class TelemetryBuffer {

    private final ConcurrentNavigableMap<Instant, TelemetrySample> samples =
            new ConcurrentSkipListMap<>();
    private final Duration retention;

    public TelemetryBuffer(OpenAdrProperties properties) {
        retention = Duration.ofSeconds(properties.getReport().getTelemetryRetentionSeconds());
    }

    public void add(TelemetrySample sample) {
        samples.put(sample.capturedAt(), sample);
        samples.headMap(sample.capturedAt().minus(retention), false).clear();
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
}
