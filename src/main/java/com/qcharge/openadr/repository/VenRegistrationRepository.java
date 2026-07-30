package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VenRegistrationRepository extends JpaRepository<VenRegistration, Long> {
    Optional<VenRegistration> findByVenIdAndStatus(
            String venId, VenRegistrationStatus status
    );

    Optional<VenRegistration> findFirstByStatusOrderByUpdatedAtDesc(VenRegistrationStatus status);

    Optional<VenRegistration> findFirstByStatusAndRegistrationIdIsNotNullOrderByUpdatedAtDesc(VenRegistrationStatus status);
}