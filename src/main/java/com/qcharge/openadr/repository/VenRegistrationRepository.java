package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.VenRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface VenRegistrationRepository extends JpaRepository<VenRegistration, Long> {
    Optional<VenRegistration> findByVenIdAndStatus(
            String venId,
            VenRegistration.RegistrationStatus status
    );
}