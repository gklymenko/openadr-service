package com.qcharge.openadr.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "openadr_resource",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_openadr_resource_charge_point_pk",
                        columnNames = "charge_point_pk"
                ),
                @UniqueConstraint(
                        name = "uk_openadr_resource_charge_point_identity",
                        columnNames = "charge_point_identity"
                ),
                @UniqueConstraint(
                        name = "uk_openadr_resource_charge_point_uuid",
                        columnNames = "charge_point_uuid"
                ),
                @UniqueConstraint(
                        name = "uk_openadr_resource_resource_id",
                        columnNames = "resource_id"
                )
        }
)
public class OpenAdrResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "charge_point_pk", nullable = false)
    private Integer chargePointPk;

    @Column(name = "charge_point_identity", nullable = false, length = 255)
    private String chargePointIdentity;

    @Column(name = "charge_point_uuid", nullable = false, length = 50)
    private String chargePointUuid;

    @Column(name = "resource_id", nullable = false, length = 64)
    private String resourceId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "max_power_watts")
    private Long maxPowerWatts;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion = 0L;

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
