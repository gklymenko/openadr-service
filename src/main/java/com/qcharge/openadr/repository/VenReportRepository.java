package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.VenReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VenReportRepository extends JpaRepository<VenReport, Long> {

    Optional<VenReport> findByReportSpecId(String reportSpecId);

    Optional<VenReport> findByReportRequestId(String reportRequestId);
}