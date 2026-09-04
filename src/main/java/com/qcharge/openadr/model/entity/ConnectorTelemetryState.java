package com.qcharge.openadr.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
        name = "connector_telemetry_state",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_connector_telemetry_resource_connector",
                columnNames = {"resource_id", "connector_number"}
        )
)
public class ConnectorTelemetryState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false, updatable = false)
    private OpenAdrResource resource;

    @Column(name = "connector_number", nullable = false, updatable = false)
    private int connectorNumber;

    @Column(name = "transaction_id")
    private Integer transactionId;

    @Column(name = "power_kw", precision = 19, scale = 6)
    private BigDecimal powerKw;

    @Column(name = "power_captured_at")
    private Instant powerCapturedAt;

    @Column(name = "energy_register_kwh", precision = 19, scale = 6)
    private BigDecimal energyRegisterKwh;

    @Column(name = "energy_captured_at")
    private Instant energyCapturedAt;

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
