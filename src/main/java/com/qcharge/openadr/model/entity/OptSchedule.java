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

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "opt_schedule",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_opt_schedule_opt_id", columnNames = "opt_id")
        }
)
public class OptSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "opt_id", nullable = false, unique = true)
    private String optId;

    @Column(name = "opt_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private OptType optType;

    @Column(name = "opt_reason")
    private String optReason;

    @Column(name = "event_id")
    private String eventId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private OptStatus status = OptStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }


    public enum OptType {
        OPT_IN, OPT_OUT
    }

    public enum OptStatus {
        ACTIVE, CANCELLED
    }
}