package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.ResourceTelemetrySample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ResourceTelemetrySampleRepository
        extends JpaRepository<ResourceTelemetrySample, Long> {

    Optional<ResourceTelemetrySample> findByResource_IdAndCapturedAt(
            Long resourceId,
            Instant capturedAt
    );

    Optional<ResourceTelemetrySample> findFirstByResource_ResourceIdOrderByCapturedAtDesc(
            String resourceId
    );

    Optional<ResourceTelemetrySample>
    findFirstByResource_ResourceIdAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
            String resourceId,
            Instant capturedAt
    );

    List<ResourceTelemetrySample>
    findByResource_ResourceIdAndCapturedAtGreaterThanEqualAndCapturedAtLessThanOrderByCapturedAtAsc(
            String resourceId,
            Instant start,
            Instant endExclusive
    );

    List<ResourceTelemetrySample> findTop100ByResource_IdOrderByCapturedAtDesc(Long resourceId);

    @Query("select distinct sample.resource.id from ResourceTelemetrySample sample")
    List<Long> findDistinctResourceIds();

    @Modifying
    @Query("""
            delete from ResourceTelemetrySample sample
            where sample.resource.id = :resourceId
              and sample.capturedAt < :cutoff
            """)
    int deleteOlderThan(
            @Param("resourceId") Long resourceId,
            @Param("cutoff") Instant cutoff
    );
}
