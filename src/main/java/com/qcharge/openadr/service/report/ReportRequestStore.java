package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.repository.ReportRequestRepository;
import com.qcharge.openadr.service.report.model.ReportSchedule;
import com.qcharge.openadr.service.report.model.ReportRidCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.qcharge.openadr.model.entity.ReportRequest.Status.ACTIVE;
import static com.qcharge.openadr.model.entity.ReportRequest.Status.CANCELLED;
import static com.qcharge.openadr.model.entity.ReportRequest.Status.FINAL_REPORT_PENDING;

@Service
@RequiredArgsConstructor
public class ReportRequestStore {

    private final ReportRequestRepository repository;

    @Transactional
    public List<ReportRequest> activateAll(
            List<ValidatedReportRequest> requests,
            Instant acceptedAt
    ) {
        return repository.saveAll(
                requests.stream()
                        .map(request -> toEntity(request, acceptedAt))
                        .toList()
        );
    }

    private ReportRequest toEntity(ValidatedReportRequest request, Instant acceptedAt) {
        ReportSchedule schedule = ReportSchedule.activate(request, acceptedAt);
        ReportRequest entity = new ReportRequest();
        entity.setReportRequestId(request.reportRequestId());
        entity.setReportSpecifierId(request.reportSpecifierId());
        entity.setReportName(request.reportName());
        entity.setRequestedRids(ReportRidCodec.encode(request.requestedRids()));
        entity.setGranularitySeconds(request.granularity().toSeconds());
        entity.setReportBackDurationSeconds(request.reportBackDuration().toSeconds());
        entity.setRequestedStart(schedule.start());
        entity.setRequestedDurationSeconds(
                request.requestedDuration() == null
                        ? null
                        : request.requestedDuration().toSeconds()
        );
        entity.setNextReportAt(schedule.oneShot() ? null : schedule.firstDeliveryAt(acceptedAt));
        entity.setStatus(ReportRequest.Status.ACTIVE);
        return entity;
    }

    @Transactional
    public boolean cancel(String reportRequestId) {
        return repository.findByReportRequestId(reportRequestId)
                .filter(request -> request.getStatus() == ReportRequest.Status.ACTIVE)
                .map(request -> {
                    request.setStatus(ReportRequest.Status.CANCELLED);
                    request.setNextReportAt(null);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public CancellationBatch beginCancellation(
            List<String> requestedIds,
            boolean reportToFollow
    ) {
        List<String> normalizedIds = requestedIds == null
                ? List.of()
                : List.copyOf(requestedIds);
        Set<String> uniqueIds = new LinkedHashSet<>(normalizedIds);

        if (uniqueIds.size() != normalizedIds.size()) {
            return CancellationBatch.rejected(duplicateIds(normalizedIds));
        }

        List<ReportRequest> requests = uniqueIds.isEmpty()
                ? repository.lockAllByStatusIn(cancellableStatuses())
                : repository.lockAllByReportRequestIdIn(uniqueIds);
        List<String> invalidIds = invalidCancellationIds(uniqueIds, requests);

        if (!invalidIds.isEmpty()) {
            return CancellationBatch.rejected(invalidIds);
        }

        ReportRequest.Status targetStatus = reportToFollow
                ? FINAL_REPORT_PENDING
                : CANCELLED;
        requests.forEach(request -> {
            request.setStatus(targetStatus);
            request.setNextReportAt(null);
        });

        return CancellationBatch.accepted(requests);
    }

    @Transactional
    public void completeFinalCancellation(String reportRequestId) {
        repository.findByReportRequestId(reportRequestId)
                .filter(request -> request.getStatus() == FINAL_REPORT_PENDING)
                .ifPresent(request -> {
                    request.setStatus(CANCELLED);
                    request.setNextReportAt(null);
                });
    }

    @Transactional
    public void cancelNonMetadataRequests() {
        repository.lockAllByStatusIn(cancellableStatuses()).stream()
                .filter(request -> !ReportService.REPORT_SPECIFIER_ID_METADATA.equalsIgnoreCase(
                        request.getReportSpecifierId()
                ))
                .forEach(request -> {
                    request.setStatus(CANCELLED);
                    request.setNextReportAt(null);
                });
    }

    @Transactional
    public void complete(String reportRequestId) {
        repository.findByReportRequestId(reportRequestId)
                .filter(request -> request.getStatus() == ReportRequest.Status.ACTIVE)
                .ifPresent(request -> {
                    request.setStatus(ReportRequest.Status.COMPLETED);
                    request.setNextReportAt(null);
                });
    }

    @Transactional
    public void recordDelivery(
            String reportRequestId,
            Instant deliveredThrough,
            Optional<Instant> nextReportAt
    ) {
        repository.findByReportRequestId(reportRequestId)
                .filter(request -> request.getStatus() == ReportRequest.Status.ACTIVE)
                .ifPresent(request -> {
                    request.setLastReportedAt(deliveredThrough);
                    request.setNextReportAt(nextReportAt.orElse(null));
                    if (nextReportAt.isEmpty()) {
                        request.setStatus(ReportRequest.Status.COMPLETED);
                    }
                });
    }

    @Transactional
    public void cancelAll() {
        repository.findAllByStatus(ReportRequest.Status.ACTIVE)
                .forEach(request -> {
                    request.setStatus(ReportRequest.Status.CANCELLED);
                    request.setNextReportAt(null);
                });
    }

    @Transactional(readOnly = true)
    public Optional<ReportRequest> findActive(String reportRequestId) {
        return repository.findByReportRequestId(reportRequestId)
                .filter(request -> request.getStatus() == ReportRequest.Status.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<ReportRequest> findAllActive() {
        return repository.findAllByStatus(ReportRequest.Status.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<ReportRequest> findFinalReportsPending() {
        return repository.findAllByStatus(FINAL_REPORT_PENDING);
    }

    @Transactional(readOnly = true)
    public List<String> findAllPendingReportRequestIds() {
        return repository.findAllByStatusInOrderByCreatedAtAsc(
                        List.of(ACTIVE, FINAL_REPORT_PENDING)
                ).stream()
                .filter(request -> request.getStatus() == FINAL_REPORT_PENDING
                        || request.getNextReportAt() != null)
                .map(ReportRequest::getReportRequestId)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReportRequest> findDue(Instant now) {
        return repository.findAllByStatusAndNextReportAtLessThanEqualOrderByNextReportAtAsc(
                ReportRequest.Status.ACTIVE,
                now
        );
    }

    private List<String> invalidCancellationIds(
            Set<String> requestedIds,
            List<ReportRequest> requests
    ) {
        if (requestedIds.isEmpty()) {
            return List.of();
        }

        Set<String> cancellableIds = requests.stream()
                .filter(request -> cancellableStatuses().contains(request.getStatus()))
                .map(ReportRequest::getReportRequestId)
                .collect(java.util.stream.Collectors.toSet());
        return requestedIds.stream()
                .filter(id -> !cancellableIds.contains(id))
                .toList();
    }

    private List<String> duplicateIds(List<String> requestedIds) {
        Set<String> seen = new LinkedHashSet<>();
        return requestedIds.stream()
                .filter(id -> !seen.add(id))
                .distinct()
                .toList();
    }

    private Collection<ReportRequest.Status> cancellableStatuses() {
        return List.of(ACTIVE, FINAL_REPORT_PENDING);
    }

    public record CancellationBatch(
            List<ReportRequest> requests,
            List<String> invalidReportRequestIds
    ) {
        public CancellationBatch {
            requests = List.copyOf(requests);
            invalidReportRequestIds = List.copyOf(invalidReportRequestIds);
        }

        static CancellationBatch accepted(List<ReportRequest> requests) {
            return new CancellationBatch(requests, List.of());
        }

        static CancellationBatch rejected(List<String> invalidIds) {
            return new CancellationBatch(List.of(), invalidIds);
        }

        public boolean accepted() {
            return invalidReportRequestIds.isEmpty();
        }
    }
}
