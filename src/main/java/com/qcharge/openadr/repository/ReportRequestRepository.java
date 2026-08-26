package com.qcharge.openadr.repository;

import com.qcharge.openadr.model.entity.ReportRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

public interface ReportRequestRepository extends JpaRepository<ReportRequest, Long> {

    Optional<ReportRequest> findByReportRequestId(String reportRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from ReportRequest request
            where request.reportRequestId = :reportRequestId
            """)
    Optional<ReportRequest> lockByReportRequestId(
            @Param("reportRequestId") String reportRequestId
    );

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
            order by request.reportRequestId
            """)
    List<ReportRequest> lockAllByReportRequestIdIn(
            @Param("reportRequestIds") Collection<String> reportRequestIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select request
            from ReportRequest request
            where request.status in :statuses
            order by request.reportRequestId
            """)
    List<ReportRequest> lockAllByStatusIn(
            @Param("statuses") Collection<ReportRequest.Status> statuses
    );

    List<ReportRequest> findAllByStatusAndNextReportAtLessThanEqualOrderByNextReportAtAsc(
            ReportRequest.Status status,
            Instant dueAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReportRequest request
            set request.deliveryState = :inProgress,
                request.deliveryToken = :token,
                request.deliveryClaimedAt = :claimedAt
            where request.reportRequestId = :reportRequestId
              and request.status = :requiredStatus
              and (
                    request.deliveryState = :idle
                    or (
                        request.deliveryState = :inProgress
                        and request.deliveryClaimedAt <= :expiredBefore
                    )
              )
            """)
    int claim(
            @Param("reportRequestId") String reportRequestId,
            @Param("requiredStatus") ReportRequest.Status requiredStatus,
            @Param("idle") ReportRequest.DeliveryState idle,
            @Param("inProgress") ReportRequest.DeliveryState inProgress,
            @Param("token") String token,
            @Param("claimedAt") Instant claimedAt,
            @Param("expiredBefore") Instant expiredBefore
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ReportRequest request
            set request.deliveryState = :inProgress,
                request.deliveryToken = :token,
                request.deliveryClaimedAt = :claimedAt
            where request.reportRequestId = :reportRequestId
              and request.status = :active
              and request.nextReportAt <= :dueAt
              and (
                    request.deliveryState = :idle
                    or (
                        request.deliveryState = :inProgress
                        and request.deliveryClaimedAt <= :expiredBefore
                    )
              )
            """)
    int claimDue(
            @Param("reportRequestId") String reportRequestId,
            @Param("active") ReportRequest.Status active,
            @Param("idle") ReportRequest.DeliveryState idle,
            @Param("inProgress") ReportRequest.DeliveryState inProgress,
            @Param("token") String token,
            @Param("claimedAt") Instant claimedAt,
            @Param("expiredBefore") Instant expiredBefore,
            @Param("dueAt") Instant dueAt
    );
}
