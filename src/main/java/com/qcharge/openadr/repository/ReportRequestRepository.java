package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.ReportRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReportRequestRepository extends JpaRepository<ReportRequest, Long> {

    Optional<ReportRequest> findByReportRequestId(String reportRequestId);

    boolean existsByReportRequestId(String reportRequestId);

    List<ReportRequest> findAllByStatus(ReportRequest.Status status);

    List<ReportRequest> findAllByStatusAndNextReportAtLessThanEqualOrderByNextReportAtAsc(
            ReportRequest.Status status,
            Instant dueAt
    );
}
