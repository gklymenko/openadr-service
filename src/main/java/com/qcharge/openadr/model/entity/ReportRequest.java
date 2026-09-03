package com.qcharge.openadr.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "report_request")
public class ReportRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_request_id", nullable = false, unique = true)
    private String reportRequestId;

    @Column(name = "report_specifier_id", nullable = false)
    private String reportSpecifierId;

    @Column(name = "report_name", nullable = false)
    private String reportName;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "requested_rids", nullable = false)
    private String requestedRids;

    @Column(name = "granularity_seconds", nullable = false)
    private long granularitySeconds;

    @Column(name = "report_back_duration_seconds", nullable = false)
    private long reportBackDurationSeconds;

    @Column(name = "requested_start")
    private Instant requestedStart;

    @Column(name = "requested_duration_seconds")
    private Long requestedDurationSeconds;

    @Column(name = "next_report_at")
    private Instant nextReportAt;

    @Column(name = "last_reported_at")
    private Instant lastReportedAt;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    /** Delivery lease is orthogonal to lifecycle so cancellation cannot hide in-flight I/O. */
    @Column(name = "delivery_state", nullable = false)
    @Enumerated(EnumType.STRING)
    private DeliveryState deliveryState = DeliveryState.IDLE;

    @Column(name = "delivery_token", length = 36)
    private String deliveryToken;

    @Column(name = "delivery_claimed_at")
    private Instant deliveryClaimedAt;

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

    public enum Status {
        ACTIVE,
        FINAL_REPORT_PENDING,
        CANCELLED,
        COMPLETED
    }

    public enum DeliveryState {
        IDLE,
        IN_PROGRESS
    }
}
