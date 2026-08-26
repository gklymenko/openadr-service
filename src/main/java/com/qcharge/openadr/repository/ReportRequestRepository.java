package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.ReportRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

public interface ReportRequestRepository extends JpaRepository<ReportRequest, Long> {

    Optional<ReportRequest> findByReportRequestId(String reportRequestId);

    boolean existsByReportRequestId(String reportRequestId);

    List<ReportRequest> findAllByStatus(ReportRequest.Status status);

    List<ReportRequest> findAllByStatusInOrderByCreatedAtAsc(
            Collection<ReportRequest.Status> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from ReportRequest request
            where request.reportRequestId in :reportRequestIds
            """)
    List<ReportRequest> lockAllByReportRequestIdIn(
            @Param("reportRequestIds") Collection<String> reportRequestIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from ReportRequest request
            where request.status in :statuses
            """)
    List<ReportRequest> lockAllByStatusIn(
            @Param("statuses") Collection<ReportRequest.Status> statuses
    );

    List<ReportRequest> findAllByStatusAndNextReportAtLessThanEqualOrderByNextReportAtAsc(
            ReportRequest.Status status,
            Instant dueAt
    );
}
