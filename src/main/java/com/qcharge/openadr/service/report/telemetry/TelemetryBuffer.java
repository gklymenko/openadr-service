package com.qcharge.openadr.service.report.telemetry;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.OpenAdrResource;
import com.qcharge.openadr.model.entity.ResourceTelemetrySample;
import com.qcharge.openadr.repository.ResourceTelemetrySampleRepository;
import com.qcharge.openadr.utility.TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Persistent per-resource telemetry history.
 *
 * The historical name is kept to minimize churn in the report pipeline; unlike the
 * former implementation this component is database-backed and safe across restarts.
 */
@Component
@RequiredArgsConstructor
public class TelemetryBuffer {

    static final int MINIMUM_RETAINED_SAMPLES = 100;

    private final ResourceTelemetrySampleRepository repository;
    private final OpenAdrProperties properties;
    private final Clock clock;

    @Transactional
    public TelemetrySample add(OpenAdrResource resource, TelemetrySample sample) {
        return add(
                resource,
                sample.capturedAt(),
                BigDecimal.valueOf(sample.powerKw()),
                BigDecimal.valueOf(sample.energyKwh()),
                sample.online()
        );
    }

    @Transactional
    public TelemetrySample add(
            OpenAdrResource resource,
            Instant timestamp,
            BigDecimal powerKw,
            BigDecimal energyKwh,
            boolean online
    ) {
        Instant capturedAt = timestamp.truncatedTo(ChronoUnit.MILLIS);
        ResourceTelemetrySample entity = repository
                .findByResource_IdAndCapturedAt(resource.getId(), capturedAt)
                .orElseGet(ResourceTelemetrySample::new);

        entity.setResource(resource);
        entity.setCapturedAt(capturedAt);
        entity.setPowerKw(powerKw);
        entity.setEnergyKwh(energyKwh);
        entity.setOnline(online);

        return toSample(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public Optional<TelemetrySample> latest(String resourceId) {
        return repository.findFirstByResource_ResourceIdOrderByCapturedAtDesc(resourceId)
                .map(this::toSample);
    }

    @Transactional(readOnly = true)
    public Optional<TelemetrySample> latestAtOrBefore(String resourceId, Instant timestamp) {
        return repository
                .findFirstByResource_ResourceIdAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
                        resourceId,
                        timestamp
                )
                .map(this::toSample);
    }

    @Transactional(readOnly = true)
    public List<TelemetrySample> samplesIn(String resourceId, TimeRange range) {
        return repository
                .findByResource_ResourceIdAndCapturedAtGreaterThanEqualAndCapturedAtLessThanOrderByCapturedAtAsc(
                        resourceId,
                        range.start(),
                        range.endExclusive()
                )
                .stream()
                .map(this::toSample)
                .toList();
    }

    @Scheduled(fixedDelayString = "${openadr.report.telemetry-cleanup-delay-millis:60000}")
    @Transactional
    public void removeExpiredSamples() {
        Instant retentionCutoff = clock.instant()
                .minusSeconds(properties.getReport().getTelemetryRetentionSeconds());

        for (Long resourceId : repository.findDistinctResourceIds()) {
            List<ResourceTelemetrySample> protectedSamples =
                    repository.findTop100ByResource_IdOrderByCapturedAtDesc(resourceId);
            if (protectedSamples.size() < MINIMUM_RETAINED_SAMPLES) {
                continue;
            }

            Instant oldestProtected = protectedSamples.get(protectedSamples.size() - 1).getCapturedAt();
            Instant cutoff = retentionCutoff.isBefore(oldestProtected)
                    ? retentionCutoff
                    : oldestProtected;
            repository.deleteOlderThan(resourceId, cutoff);
        }
    }

    private TelemetrySample toSample(ResourceTelemetrySample entity) {
        boolean online = entity.isOnline();
        return new TelemetrySample(
                entity.getCapturedAt(),
                entity.getPowerKw().floatValue(),
                entity.getEnergyKwh().floatValue(),
                online,
                false,
                online ? 1.0f : 0.0f,
                1.0f,
                0.0f,
                1.0f
        );
    }
}
