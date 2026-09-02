package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.OpenAdrResource;
import org.springframework.data.jpa.repository.JpaRepository;

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

    boolean existsByChargePointIdentityAndChargePointPkNot(
            String chargePointIdentity,
            Integer chargePointPk
    );

    boolean existsByChargePointUuidAndChargePointPkNot(
            String chargePointUuid,
            Integer chargePointPk
    );
}
