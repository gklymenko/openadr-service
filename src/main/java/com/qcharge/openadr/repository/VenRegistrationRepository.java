package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface VenRegistrationRepository extends JpaRepository<VenRegistration, Long> {
    Optional<VenRegistration> findByVenIdAndStatus(
            String venId, VenRegistrationStatus status
    );

    Optional<VenRegistration> findFirstByStatusOrderByUpdatedAtDesc(VenRegistrationStatus status);

    Optional<VenRegistration> findFirstByStatusAndRegistrationIdIsNotNullOrderByUpdatedAtDesc(VenRegistrationStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update VenRegistration registration
               set registration.status = :targetStatus,
                   registration.updatedAt = :updatedAt
             where registration.id = :id
               and registration.venId = :venId
               and registration.registrationId = :registrationId
               and registration.status = :expectedStatus
            """)
    int transitionStatus(
            @Param("id") Long id,
            @Param("venId") String venId,
            @Param("registrationId") String registrationId,
            @Param("expectedStatus") VenRegistrationStatus expectedStatus,
            @Param("targetStatus") VenRegistrationStatus targetStatus,
            @Param("updatedAt") Instant updatedAt
    );
}
