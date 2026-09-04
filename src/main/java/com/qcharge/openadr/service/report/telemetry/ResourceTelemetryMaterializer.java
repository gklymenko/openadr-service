package com.qcharge.openadr.service.report.telemetry;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.repository.OpenAdrResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Periodically turns the latest normalized connector state into resource-scoped
 * report history. This also expires stale power to zero when no new meter value arrives.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ResourceTelemetryMaterializer {

    private final OpenAdrResourceRepository resourceRepository;
    private final ResourceTelemetryMaterializationWorker worker;
    private final OpenAdrProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${openadr.report.telemetry-sampler-delay-millis:60000}")
    public void materializeLatestState() {
        Instant capturedAt = clock.instant();
        resourceRepository
                .findAllByVenKeyAndEnabledTrueOrderByResourceIdAsc(properties.getVen().getKey())
                .forEach(resource -> {
                    try {
                        worker.materialize(resource.getId(), capturedAt);
                    } catch (RuntimeException exception) {
                        log.error(
                                "Failed to materialize telemetry for resourceId={}",
                                resource.getResourceId(),
                                exception
                        );
                    }
                });
    }
}
