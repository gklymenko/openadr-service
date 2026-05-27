package com.qcharge.openadr.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "opt_schedule")
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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum OptType {
        OPT_IN, OPT_OUT
    }

    public enum OptStatus {
        ACTIVE, CANCELLED
    }
}