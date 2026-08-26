package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.repository.ReportRequestRepository;
import com.qcharge.openadr.service.report.model.ReportSchedule;
import com.qcharge.openadr.service.report.model.ReportRidCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
    public List<ReportRequest> findDue(Instant now) {
        return repository.findAllByStatusAndNextReportAtLessThanEqualOrderByNextReportAtAsc(
                ReportRequest.Status.ACTIVE,
                now
        );
    }
}
