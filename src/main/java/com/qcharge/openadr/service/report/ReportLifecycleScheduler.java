package com.qcharge.openadr.service.report;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lightweight due-time trigger; delivery and persistence are coordinated separately. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportLifecycleScheduler {

    private final ReportDeliveryCoordinator deliveryCoordinator;

    @Scheduled(fixedDelayString = "${openadr.report.scheduler-delay-millis:1000}")
    public void processDueReports() {
        try {
            deliveryCoordinator.deliverDueReports();
        } catch (RuntimeException exception) {
            log.debug("Report scheduler skipped: {}", exception.getMessage());
        }
    }
}
