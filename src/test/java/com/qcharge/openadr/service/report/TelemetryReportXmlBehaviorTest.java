package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.entity.ReportRequest;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPayloadResourceStatusType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.service.report.model.ReportDataInterval;
import com.qcharge.openadr.service.report.model.ReportDataQuality;
import com.qcharge.openadr.service.report.model.ReportRidCodec;
import com.qcharge.openadr.service.report.model.ReportSchedule;
import com.qcharge.openadr.service.report.telemetry.TelemetrySample;
import com.qcharge.openadr.utility.TimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryReportXmlBehaviorTest {

    private static final Instant NOW = Instant.parse("2026-08-26T10:00:00Z");

    private TelemetryStatusReportFactory statusFactory;
    private TelemetryUsageReportFactory usageFactory;

    @BeforeEach
    void setUp() {
        TelemetryReportPayloadFactory payloadFactory = new TelemetryReportPayloadFactory();
        statusFactory = new TelemetryStatusReportFactory(payloadFactory);
        usageFactory = new TelemetryUsageReportFactory(payloadFactory);
    }

    @Test
    void r1_3010_oneShotStatusContainsOnlyOverallDtstart() {
        OadrReportType report = statusFactory.oneShot(
                request(
                        "STATUS-REQUEST",
                        ReportService.REPORT_SPECIFIER_ID_TELEMETRY_STATUS,
                        ReportService.RID_RESOURCE_STATUS
                ),
                List.of(interval(NOW, Duration.ofMinutes(1)))
        );

        assertThat(report.getDtstart()).isNotNull();
        assertThat(report.getDuration()).isNull();
        assertThat(report.getIntervals().getInterval()).hasSize(1);
        var interval = report.getIntervals().getInterval().getFirst();
        assertThat(interval.getDtstart()).isNull();
        assertThat(interval.getDuration()).isNull();
        assertThat(interval.getUid()).isNotNull();
        assertThat(interval.getUid().getText()).isEqualTo("0");

        OadrPayloadResourceStatusType status = statusPayload(report);
        assertThat(status.isOadrOnline()).isTrue();
        assertThat(status.isOadrManualOverride()).isFalse();
        assertThat(status.getOadrLoadControlState()).isNull();
    }

    @Test
    void periodicStatusUsesZeroBasedIntervalUids() {
        ReportRequest request = request(
                "STATUS-REQUEST",
                ReportService.REPORT_SPECIFIER_ID_TELEMETRY_STATUS,
                ReportService.RID_RESOURCE_STATUS
        );
        var schedule = new ReportSchedule(
                NOW,
                NOW.plus(Duration.ofMinutes(2)),
                Duration.ofMinutes(1),
                Duration.ofMinutes(2)
        );

        OadrReportType report = statusFactory.periodic(
                request,
                schedule,
                List.of(
                        interval(NOW, Duration.ofMinutes(1)),
                        interval(NOW.plus(Duration.ofMinutes(1)), Duration.ofMinutes(1))
                ),
                NOW.plus(Duration.ofMinutes(2))
        );

        assertThat(report.getIntervals().getInterval())
                .extracting(interval -> interval.getUid().getText())
                .containsExactly("0", "1");
    }

    @Test
    void r1_3150_oneShotUsagePlacesPowerAndEnergyInOneInterval() {
        ReportRequest request = request(
                "USAGE-REQUEST",
                ReportService.REPORT_SPECIFIER_ID_TELEMETRY_USAGE,
                ReportService.RID_POWER,
                ReportService.RID_ENERGY
        );

        OadrReportType report = usageFactory.build(
                request,
                List.of(interval(NOW, Duration.ofMinutes(1))),
                NOW
        );

        assertThat(report.getDtstart()).isNotNull();
        assertThat(report.getDuration()).isNotNull();
        assertThat(report.getIntervals().getInterval()).hasSize(1);
        var interval = report.getIntervals().getInterval().getFirst();
        assertThat(interval.getDtstart()).isNotNull();
        assertThat(interval.getDuration()).isNotNull();
        assertThat(interval.getStreamPayloadBase())
                .extracting(element -> ((OadrReportPayloadType) element.getValue()).getRID())
                .containsExactly(
                        ReportService.RID_POWER,
                        ReportService.RID_ENERGY
                );
    }

    @Test
    void r1_3160_periodicPowerUsageContainsTwoIntervalsWithoutUidOrDuration() {
        OadrReportType report = usageFactory.build(
                request(
                        "POWER-REQUEST",
                        ReportService.REPORT_SPECIFIER_ID_TELEMETRY_USAGE,
                        ReportService.RID_POWER
                ),
                List.of(
                        interval(NOW, Duration.ofMinutes(1)),
                        interval(NOW.plus(Duration.ofMinutes(1)), Duration.ofMinutes(1))
                ),
                NOW.plus(Duration.ofMinutes(2))
        );

        assertThat(report.getDuration()).isNull();
        assertThat(report.getIntervals().getInterval()).hasSize(2).allSatisfy(interval -> {
            assertThat(interval.getDtstart()).isNotNull();
            assertThat(interval.getDuration()).isNull();
            assertThat(interval.getUid()).isNull();
            assertThat(interval.getStreamPayloadBase()).hasSize(1);
        });
    }

    private OadrPayloadResourceStatusType statusPayload(OadrReportType report) {
        var interval = report.getIntervals().getInterval().getFirst();
        OadrReportPayloadType payload = (OadrReportPayloadType) interval
                .getStreamPayloadBase().getFirst().getValue();
        return (OadrPayloadResourceStatusType) payload.getPayloadBase().getValue();
    }

    private ReportDataInterval interval(Instant start, Duration duration) {
        return new ReportDataInterval(
                TimeRange.of(start, duration),
                new TelemetrySample(
                        start,
                        7.5f,
                        3.25f,
                        true,
                        false,
                        0.7f,
                        1.0f,
                        0.0f,
                        1.0f
                ),
                ReportDataQuality.GOOD
        );
    }

    private ReportRequest request(
            String requestId,
            String specifierId,
            String... rids
    ) {
        ReportRequest request = new ReportRequest();
        request.setReportRequestId(requestId);
        request.setReportSpecifierId(specifierId);
        request.setRequestedRids(ReportRidCodec.encode(
                new LinkedHashSet<>(List.of(rids))
        ));
        return request;
    }
}
