package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.ReportCapability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReportCapabilityRepository extends JpaRepository<ReportCapability, Long> {

    Optional<ReportCapability> findByReportSpecifierId(String reportSpecifierId);
}
