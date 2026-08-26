package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportCapability;
import com.qcharge.openadr.repository.ReportCapabilityRepository;
import com.qcharge.openadr.repository.ReportRequestRepository;
import com.qcharge.openadr.service.report.model.ReportRidCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportCapabilityRegistry {

    private final ReportCapabilityRepository capabilityRepository;
    private final ReportRequestRepository requestRepository;

    @Transactional
    public void replaceAll(Collection<Definition> definitions) {
        requestRepository.deleteAllInBatch();
        capabilityRepository.deleteAllInBatch();

        capabilityRepository.saveAll(definitions.stream().map(this::toEntity).toList());
    }

    private ReportCapability toEntity(Definition definition) {
        ReportCapability capability = new ReportCapability();
        capability.setReportSpecifierId(definition.reportSpecifierId());
        capability.setReportName(definition.reportName());
        capability.setSupportedRids(ReportRidCodec.encode(definition.supportedRids()));
        capability.setMinSamplingPeriodSeconds(definition.minSamplingPeriod().toSeconds());
        capability.setMaxSamplingPeriodSeconds(definition.maxSamplingPeriod().toSeconds());
        capability.setAvailableDurationSeconds(definition.availableDuration().toSeconds());
        return capability;
    }

    public record Definition(
            String reportSpecifierId,
            String reportName,
            Set<String> supportedRids,
            Duration minSamplingPeriod,
            Duration maxSamplingPeriod,
            Duration availableDuration
    ) {
        public Definition {
            reportSpecifierId = requireText(reportSpecifierId, "reportSpecifierId");
            reportName = requireText(reportName, "reportName");
            Objects.requireNonNull(supportedRids, "supportedRids");
            Objects.requireNonNull(minSamplingPeriod, "minSamplingPeriod");
            Objects.requireNonNull(maxSamplingPeriod, "maxSamplingPeriod");
            Objects.requireNonNull(availableDuration, "availableDuration");

            supportedRids = Set.copyOf(new LinkedHashSet<>(supportedRids));
            if (supportedRids.isEmpty() || supportedRids.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("supportedRids must contain non-blank values");
            }
            if (minSamplingPeriod.isZero() || minSamplingPeriod.isNegative()) {
                throw new IllegalArgumentException("minSamplingPeriod must be positive");
            }
            if (maxSamplingPeriod.compareTo(minSamplingPeriod) < 0) {
                throw new IllegalArgumentException("maxSamplingPeriod must not be less than minSamplingPeriod");
            }
            if (availableDuration.compareTo(maxSamplingPeriod) < 0) {
                throw new IllegalArgumentException("availableDuration must cover maxSamplingPeriod");
            }
        }

        private static String requireText(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return value;
        }
    }
}
