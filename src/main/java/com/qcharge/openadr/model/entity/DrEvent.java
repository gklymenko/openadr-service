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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "dr_event",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_dr_event_event_id", columnNames = "event_id")
        }
)
public class DrEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "modification_number", nullable = false)
    private Integer modificationNumber = 0;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private EventStatus status;

    @Column(name = "opt_type")
    @Enumerated(EnumType.STRING)
    private OptType optType;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "signal_name")
    private String signalName;

    @Column(name = "signal_type")
    private String signalType;

    @Column(name = "signal_value", precision = 10, scale = 3)
    private BigDecimal signalValue;

    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;

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

    public enum EventStatus {
        FAR,
        NEAR,
        ACTIVE,
        COMPLETED,
        CANCELLED
    }

    public enum OptType {
        OPT_IN,
        OPT_OUT
    }
}