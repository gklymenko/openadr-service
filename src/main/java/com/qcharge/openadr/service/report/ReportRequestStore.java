package com.qcharge.openadr.service.report;

import com.qcharge.openadr.config.OpenAdrProperties;
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
import java.util.UUID;

import static com.qcharge.openadr.model.entity.ReportRequest.DeliveryState.IDLE;
import static com.qcharge.openadr.model.entity.ReportRequest.DeliveryState.IN_PROGRESS;
import static com.qcharge.openadr.model.entity.ReportRequest.Status.ACTIVE;
import static com.qcharge.openadr.model.entity.ReportRequest.Status.CANCELLED;
import static com.qcharge.openadr.model.entity.ReportRequest.Status.FINAL_REPORT_PENDING;

/** Transactional state machine for report lifecycle and cross-process delivery leases. */
@Service
@RequiredArgsConstructor
public class ReportRequestStore {

    private final ReportRequestRepository repository;
    private final OpenAdrProperties properties;

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
        entity.setResourceId(request.resourceId());
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
        entity.setDeliveryState(IDLE);
        return entity;
    }

    @Transactional
    public boolean cancel(String reportRequestId) {
        return repository.lockByReportRequestId(reportRequestId)
                .filter(request -> cancellableStatuses().contains(request.getStatus()))
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
    public boolean completeFinalCancellation(DeliveryClaim claim) {
        return lockOwnedClaim(claim)
                .map(request -> {
                    if (request.getStatus() != FINAL_REPORT_PENDING) {
                        clearDeliveryClaim(request);
                        return false;
                    }
                    request.setStatus(CANCELLED);
                    request.setNextReportAt(null);
                    clearDeliveryClaim(request);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public void cancelNonMetadataRequests() {
        repository.lockAllByStatusIn(cancellableStatuses()).stream()
                .filter(request ->
                        !ReportService.REPORT_SPECIFIER_ID_METADATA.equalsIgnoreCase(request.getReportSpecifierId())
                )
                .forEach(request -> {
                    request.setStatus(CANCELLED);
                    request.setNextReportAt(null);
                });
    }

    @Transactional
    public boolean completeDelivery(DeliveryClaim claim) {
        return lockOwnedClaim(claim)
                .map(request -> {
                    if (request.getStatus() == ACTIVE) {
                        request.setStatus(ReportRequest.Status.COMPLETED);
                        request.setNextReportAt(null);
                    }
                    clearDeliveryClaim(request);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean recordDelivery(
            DeliveryClaim claim,
            Instant deliveredThrough,
            Optional<Instant> nextReportAt
    ) {
        return lockOwnedClaim(claim)
                .map(request -> {
                    request.setLastReportedAt(deliveredThrough);
                    if (request.getStatus() == ACTIVE) {
                        request.setNextReportAt(nextReportAt.orElse(null));
                        if (nextReportAt.isEmpty()) {
                            request.setStatus(ReportRequest.Status.COMPLETED);
                        }
                    }
                    clearDeliveryClaim(request);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean releaseDelivery(DeliveryClaim claim) {
        return lockOwnedClaim(claim)
                .map(request -> {
                    clearDeliveryClaim(request);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public Optional<DeliveryClaim> claimActive(
            String reportRequestId,
            Instant claimedAt
    ) {
        return claim(reportRequestId, ACTIVE, claimedAt);
    }

    @Transactional
    public Optional<DeliveryClaim> claimFinal(
            String reportRequestId,
            Instant claimedAt
    ) {
        return claim(reportRequestId, FINAL_REPORT_PENDING, claimedAt);
    }

    @Transactional
    public Optional<DeliveryClaim> claimDue(
            String reportRequestId,
            Instant dueAt
    ) {
        String token = UUID.randomUUID().toString();
        int claimed = repository.claimDue(
                reportRequestId,
                ACTIVE,
                IDLE,
                IN_PROGRESS,
                token,
                dueAt,
                leaseExpiredBefore(dueAt),
                dueAt
        );
        return claimed == 1
                ? Optional.of(requireLoadedClaim(reportRequestId, token))
                : Optional.empty();
    }

    @Transactional
    public void cancelAll() {
        repository.lockAllByStatusIn(cancellableStatuses())
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

    private Optional<DeliveryClaim> claim(
            String reportRequestId,
            ReportRequest.Status requiredStatus,
            Instant claimedAt
    ) {
        String token = UUID.randomUUID().toString();
        int claimed = repository.claim(
                reportRequestId,
                requiredStatus,
                IDLE,
                IN_PROGRESS,
                token,
                claimedAt,
                leaseExpiredBefore(claimedAt)
        );
        return claimed == 1
                ? Optional.of(requireLoadedClaim(reportRequestId, token))
                : Optional.empty();
    }

    private Instant leaseExpiredBefore(Instant claimedAt) {
        return claimedAt.minusSeconds(
                properties.getReport().getDeliveryLeaseSeconds()
        );
    }

    private DeliveryClaim requireLoadedClaim(
            String reportRequestId,
            String token
    ) {
        return repository.findByReportRequestId(reportRequestId)
                .filter(request -> token.equals(request.getDeliveryToken()))
                .map(request -> new DeliveryClaim(request, token))
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed report could not be reloaded: " + reportRequestId
                ));
    }

    private Optional<ReportRequest> lockOwnedClaim(DeliveryClaim claim) {
        return repository.lockByReportRequestId(claim.reportRequestId())
                .filter(request -> request.getDeliveryState() == IN_PROGRESS)
                .filter(request -> claim.token().equals(request.getDeliveryToken()));
    }

    private void clearDeliveryClaim(ReportRequest request) {
        request.setDeliveryState(IDLE);
        request.setDeliveryToken(null);
        request.setDeliveryClaimedAt(null);
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

    public record DeliveryClaim(
            ReportRequest request,
            String token
    ) {
        public DeliveryClaim {
            java.util.Objects.requireNonNull(request, "request");
            java.util.Objects.requireNonNull(token, "token");
        }

        public String reportRequestId() {
            return request.getReportRequestId();
        }
    }
}
