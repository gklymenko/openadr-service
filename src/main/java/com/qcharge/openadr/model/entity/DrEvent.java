package com.qcharge.openadr.model.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "modification_number", nullable = false)
    private Integer modificationNumber = 0;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private EventStatus status;

    @Column(name = "vtn_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private EventStatus vtnStatus;

    @Column(name = "execution_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ExecutionStatus executionStatus = ExecutionStatus.RECEIVED;

    @Column(name = "opt_type")
    @Enumerated(EnumType.STRING)
    private OptType optType;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "test_event", nullable = false)
    private boolean testEvent;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "requested_start_time", nullable = false)
    private Instant requestedStartTime;

    @Column(name = "start_after_seconds", nullable = false)
    private Long startAfterSeconds = 0L;

    @Column(name = "random_offset_seconds", nullable = false)
    private Long randomOffsetSeconds = 0L;

    @Column(name = "ramp_up_seconds")
    private Long rampUpSeconds;

    @Column(name = "recovery_seconds")
    private Long recoverySeconds;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "last_applied_interval", nullable = false)
    private Integer lastAppliedInterval = -1;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancellation_type")
    @Enumerated(EnumType.STRING)
    private CancellationType cancellationType;

    @Column(name = "cancellation_requested_at")
    private Instant cancellationRequestedAt;

    @Column(name = "cancellation_effective_at")
    private Instant cancellationEffectiveAt;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNumber ASC")
    private List<DrEventSignal> signals = new ArrayList<>();

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

    public void replaceSignals(List<DrEventSignal> replacements) {
        signals.clear();
        replacements.forEach(signal -> {
            signal.setEvent(this);
            signals.add(signal);
        });
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

    public enum ExecutionStatus {
        RECEIVED,
        SCHEDULED,
        APPLIED,
        CANCEL_PENDING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public enum CancellationType {
        EXPLICIT,
        IMPLICIT
    }
}
