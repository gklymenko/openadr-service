package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.OpenAdrResource;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OpenAdrResourceRepository extends JpaRepository<OpenAdrResource, Long> {

    Optional<OpenAdrResource> findByChargePointPk(Integer chargePointPk);

    Optional<OpenAdrResource> findByVenKeyAndChargePointPk(String venKey, Integer chargePointPk);

    List<OpenAdrResource> findAllByVenKeyAndChargePointPkIn(
            String venKey,
            Collection<Integer> chargePointPks
    );

    List<OpenAdrResource> findAllByVenKeyAndEnabledTrueOrderByResourceIdAsc(String venKey);

    List<OpenAdrResource> findAllByVenKeyAndResourceIdInAndEnabledTrue(
            String venKey,
            Collection<String> resourceIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select resource
            from OpenAdrResource resource
            where resource.venKey = :venKey
              and upper(resource.chargePointIdentity) = upper(:chargePointIdentity)
              and resource.enabled = true
            """)
    Optional<OpenAdrResource> lockEnabledByChargePointIdentity(
            @Param("venKey") String venKey,
            @Param("chargePointIdentity") String chargePointIdentity
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select resource
            from OpenAdrResource resource
            where resource.id = :resourceId
              and resource.enabled = true
            """)
    Optional<OpenAdrResource> lockEnabledById(
            @Param("resourceId") Long resourceId
    );

    boolean existsByChargePointIdentityAndChargePointPkNot(
            String chargePointIdentity,
            Integer chargePointPk
    );

}
