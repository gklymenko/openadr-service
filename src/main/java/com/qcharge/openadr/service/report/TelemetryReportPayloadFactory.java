package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDataQualityType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrLoadControlStateType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrLoadControlStateTypeType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPayloadResourceStatusType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportPayloadType;
import com.qcharge.openadr.service.report.model.ReportDataQuality;
import com.qcharge.openadr.service.report.telemetry.TelemetrySample;
import org.springframework.stereotype.Component;

@Component
public class TelemetryReportPayloadFactory {

    public OadrReportPayloadType usage(
            String rid,
            TelemetrySample sample,
            ReportDataQuality quality
    ) {
        float value = switch (rid) {
            case ReportService.RID_POWER -> sample.powerKw();
            case ReportService.RID_ENERGY -> sample.energyKwh();
            default -> throw new IllegalArgumentException("Unsupported usage rID=" + rid);
        };
        OadrReportPayloadType payload = Oadr20bFactory.createReportPayloadType(
                rid, null, null, value
        );
        payload.setOadrDataQuality(dataQuality(quality).value());
        return payload;
    }

    public OadrReportPayloadType status(
            TelemetrySample sample,
            ReportDataQuality quality,
            boolean includeFullLoadControlState
    ) {
        OadrLoadControlStateType loadControlState = includeFullLoadControlState
                ? fullLoadControlState(sample)
                : null;
        OadrPayloadResourceStatusType status = Oadr20bFactory.createOadrPayloadResourceStatusType(
                loadControlState,
                sample.manualOverride(),
                sample.online()
        );
        OadrReportPayloadType payload = Oadr20bFactory.createReportPayloadType(
                ReportService.RID_RESOURCE_STATUS, null, null, status
        );
        payload.setOadrDataQuality(dataQuality(quality).value());
        return payload;
    }

    private OadrLoadControlStateType fullLoadControlState(TelemetrySample sample) {
        var capacity = Oadr20bFactory.createOadrLoadControlStateTypeType(
                sample.capacityCurrent(),
                sample.capacityNormal(),
                sample.capacityMin(),
                sample.capacityMax()
        );
        var levelOffset = zeroState();
        var percentOffset = zeroState();
        var setPoint = zeroState();
        return Oadr20bFactory.createOadrLoadControlStateType(
                capacity,
                levelOffset,
                percentOffset,
                setPoint
        );
    }

    private OadrLoadControlStateTypeType zeroState() {
        return Oadr20bFactory.createOadrLoadControlStateTypeType(
                0.0f, 0.0f, 0.0f, 0.0f
        );
    }

    private OadrDataQualityType dataQuality(ReportDataQuality quality) {
        return switch (quality) {
            case GOOD -> OadrDataQualityType.QUALITY_GOOD_NON_SPECIFIC;
            case NO_NEW_VALUE -> OadrDataQualityType.NO_NEW_VALUE_PREVIOUS_VALUE_USED;
            case BAD_NO_DATA -> OadrDataQualityType.QUALITY_BAD_LAST_KNOWN_VALUE;
        };
    }
}
