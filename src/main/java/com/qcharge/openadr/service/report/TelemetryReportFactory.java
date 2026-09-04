package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.ei.ReportNameEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.service.report.model.ReportSchedule;
import com.qcharge.openadr.utility.TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Selects the report-specific XML mapper without mixing status and usage semantics. */
@Component
@RequiredArgsConstructor
public class TelemetryReportFactory {

    private final TelemetryIntervalDataProvider intervalDataProvider;
    private final TelemetryStatusReportFactory statusReportFactory;
    private final TelemetryUsageReportFactory usageReportFactory;

    public OadrReportType oneShot(ReportRequest request) {
        var intervals = intervalDataProvider.oneShot(request);
        return isStatus(request)
                ? statusReportFactory.oneShot(request, intervals)
                : usageReportFactory.build(request, intervals, intervals.getFirst().sample().capturedAt());
    }

    public OadrReportType periodic(
            ReportRequest request,
            ReportSchedule schedule,
            TimeRange deliveryWindow,
            Instant createdAt
    ) {
        var intervals = intervalDataProvider.periodic(request, schedule, deliveryWindow);
        return isStatus(request)
                ? statusReportFactory.periodic(request, schedule, intervals, createdAt)
                : usageReportFactory.build(request, intervals, createdAt);
    }

    private boolean isStatus(ReportRequest request) {
        return ReportNameEnumeratedType.TELEMETRY_STATUS.value().equals(
                request.getReportName()
        );
    }
}
