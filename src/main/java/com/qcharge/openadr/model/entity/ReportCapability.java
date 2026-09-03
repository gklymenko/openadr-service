package com.qcharge.openadr.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "report_capability")
public class ReportCapability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_specifier_id", nullable = false, unique = true)
    private String reportSpecifierId;

    @Column(name = "report_name", nullable = false)
    private String reportName;

    @Column(name = "resource_id", nullable = false, length = 64)
    private String resourceId;

    @Column(name = "supported_rids", nullable = false)
    private String supportedRids;

    @Column(name = "min_sampling_period_seconds", nullable = false)
    private long minSamplingPeriodSeconds;

    @Column(name = "max_sampling_period_seconds", nullable = false)
    private long maxSamplingPeriodSeconds;

    @Column(name = "available_duration_seconds", nullable = false)
    private long availableDurationSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
