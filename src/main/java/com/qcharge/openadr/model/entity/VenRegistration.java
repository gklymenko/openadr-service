package com.qcharge.openadr.model.entity;

import com.qcharge.openadr.model.enums.VenRegistrationStatus;
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
@Table(name = "ven_registration")
public class VenRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ven_id", nullable = false)
    private String venId;

    @Column(name = "vtn_id")
    private String vtnId;

    @Column(name = "registration_id")
    private String registrationId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private VenRegistrationStatus status = VenRegistrationStatus.PENDING;

    @Column(name = "requested_poll_frequency", length = 64)
    private String requestedPollFrequency;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        registeredAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}