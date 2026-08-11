package com.qcharge.openadr.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "dr_event_interval",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dr_event_interval_signal_uid",
                columnNames = {"signal_id", "interval_uid"}
        )
)
public class DrEventInterval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "signal_id", nullable = false)
    private DrEventSignal signal;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "interval_uid", nullable = false, length = 64)
    private String intervalUid;

    @Column(name = "duration_seconds", nullable = false)
    private Long durationSeconds;

    @Column(name = "payload_value", nullable = false, precision = 19, scale = 6)
    private BigDecimal payloadValue;
}
