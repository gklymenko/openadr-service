package com.qcharge.openadr.service.report;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.entity.ReportCapability;
import com.qcharge.openadr.model.oadr20b.ei.ReadingTypeEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.ReportSpecifierType;
import com.qcharge.openadr.model.oadr20b.ei.SpecifierPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.model.oadr20b.xcal.Properties;
import com.qcharge.openadr.repository.ReportCapabilityRepository;
import com.qcharge.openadr.repository.ReportRequestRepository;
import com.qcharge.openadr.service.report.model.ReportRidCodec;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ReportRequestValidator {

    private static final String REQUIRED_READING_TYPE =
            ReadingTypeEnumeratedType.X_NOT_APPLICABLE.value();

    private final ReportCapabilityRepository capabilityRepository;
    private final ReportRequestRepository requestRepository;

    @Transactional(readOnly = true)
    public List<ValidatedReportRequest> validateAll(
            List<OadrReportRequestType> requests,
            String payloadRequestId
    ) {
        if (requests == null || requests.isEmpty()) {
            throw invalidData("oadrCreateReport must contain at least one report request", payloadRequestId);
        }

        Set<String> requestIds = new HashSet<>();
        return requests.stream()
                .map(request -> validate(request, payloadRequestId, requestIds))
                .toList();
    }

    private ValidatedReportRequest validate(
            OadrReportRequestType request,
            String payloadRequestId,
            Set<String> requestIds
    ) {
        if (request == null) {
            throw invalidData("oadrReportRequest is required", payloadRequestId);
        }

        String reportRequestId = requireId(
                request.getReportRequestID(),
                "reportRequestID",
                payloadRequestId
        );

        if (!requestIds.add(reportRequestId)
                || requestRepository.existsByReportRequestId(reportRequestId)) {
            throw invalidId("reportRequestID", "unique value", reportRequestId, payloadRequestId);
        }

        ReportSpecifierType specifier = request.getReportSpecifier();
        if (specifier == null) {
            throw invalidData("reportSpecifier is required", payloadRequestId);
        }

        String reportSpecifierId = requireId(
                specifier.getReportSpecifierID(),
                "reportSpecifierID",
                payloadRequestId
        );
        Duration granularity = requireDuration(
                specifier.getGranularity() == null
                        ? null
                        : specifier.getGranularity().getDuration(),
                "granularity",
                payloadRequestId
        );
        Duration reportBackDuration = requireDuration(
                specifier.getReportBackDuration() == null
                        ? null
                        : specifier.getReportBackDuration().getDuration(),
                "reportBackDuration",
                payloadRequestId
        );

        validateNonNegative(granularity, "granularity", payloadRequestId);
        validateNonNegative(reportBackDuration, "reportBackDuration", payloadRequestId);
        if (!reportBackDuration.isZero()) {
            validateReportIntervalProperties(specifier, payloadRequestId);
        }

        ReportInterval reportInterval = reportInterval(specifier, reportBackDuration, payloadRequestId);
        Set<String> requestedRids = validateSpecifierPayloads(
                specifier.getSpecifierPayload(),
                payloadRequestId
        );

        if (ReportService.REPORT_SPECIFIER_ID_METADATA.equalsIgnoreCase(reportSpecifierId)) {
            if (!Set.of("0").equals(requestedRids)) {
                throw invalidId("rID", "0 for METADATA", requestedRids.toString(), payloadRequestId);
            }

            return new ValidatedReportRequest(
                    reportRequestId,
                    ReportService.REPORT_SPECIFIER_ID_METADATA,
                    ReportService.REPORT_SPECIFIER_ID_METADATA,
                    requestedRids,
                    granularity,
                    reportBackDuration,
                    reportInterval.start(),
                    reportInterval.duration(),
                    true
            );
        }

        ReportCapability capability = capabilityRepository
                .findByReportSpecifierId(reportSpecifierId)
                .orElseThrow(() -> invalidId(
                        "reportSpecifierID",
                        "previously offered reportSpecifierID",
                        reportSpecifierId,
                        payloadRequestId
                ));

        if (requestedRids.isEmpty()) {
            throw invalidData("At least one rID must be requested", payloadRequestId);
        }

        Set<String> supportedRids = ReportRidCodec.decode(capability.getSupportedRids());
        requestedRids.stream()
                .filter(rid -> !supportedRids.contains(rid))
                .findFirst()
                .ifPresent(rid -> {
                    throw invalidId("rID", supportedRids.toString(), rid, payloadRequestId);
                });

        validateSamplingRange(capability, granularity, reportBackDuration, payloadRequestId);
        validateRequestedDuration(capability, reportInterval.duration(), payloadRequestId);

        return new ValidatedReportRequest(
                reportRequestId,
                reportSpecifierId,
                capability.getReportName(),
                requestedRids,
                granularity,
                reportBackDuration,
                reportInterval.start(),
                reportInterval.duration(),
                false
        );
    }

    private Set<String> validateSpecifierPayloads(
            List<SpecifierPayloadType> payloads,
            String payloadRequestId
    ) {
        if (payloads == null || payloads.isEmpty()) {
            throw invalidData("specifierPayload is required", payloadRequestId);
        }

        Set<String> rids = new LinkedHashSet<>();
        for (SpecifierPayloadType payload : payloads) {
            if (payload == null) {
                throw invalidData("specifierPayload entry is required", payloadRequestId);
            }

            String rid = requireId(payload.getRID(), "rID", payloadRequestId);
            if (!rids.add(rid)) {
                throw invalidId("rID", "unique value within report request", rid, payloadRequestId);
            }

            if (!REQUIRED_READING_TYPE.equals(payload.getReadingType())) {
                throw invalidData(
                        "specifierPayload.readingType must be " + REQUIRED_READING_TYPE,
                        payloadRequestId
                );
            }

            if (payload.getItemBase() != null) {
                throw invalidData("specifierPayload.itemBase must be omitted", payloadRequestId);
            }
        }
        return rids;
    }

    private ReportInterval reportInterval(
            ReportSpecifierType specifier,
            Duration reportBackDuration,
            String payloadRequestId
    ) {
        if (reportBackDuration.isZero()) {
            return ReportInterval.NONE;
        }

        if (specifier.getReportInterval() == null) {
            throw invalidData(
                    "reportInterval is required for a periodic report",
                    payloadRequestId
            );
        }

        Properties properties = specifier.getReportInterval().getProperties();
        if (properties == null
                || properties.getDtstart() == null
                || properties.getDtstart().getDateTime() == null
                || properties.getDuration() == null) {
            throw invalidData(
                    "reportInterval must contain dtstart and duration",
                    payloadRequestId
            );
        }

        Duration duration = requireDuration(
                properties.getDuration().getDuration(),
                "reportInterval.duration",
                payloadRequestId
        );
        validateNonNegative(duration, "reportInterval.duration", payloadRequestId);

        try {
            return new ReportInterval(
                    OpenAdrTimeUtils.fromXmlDateTime(properties.getDtstart().getDateTime()),
                    duration
            );
        } catch (RuntimeException exception) {
            throw invalidData("reportInterval.dtstart is not a valid date-time", payloadRequestId);
        }
    }

    private void validateReportIntervalProperties(
            ReportSpecifierType specifier,
            String payloadRequestId
    ) {
        if (specifier.getReportInterval() == null
                || specifier.getReportInterval().getProperties() == null) {
            return;
        }

        Properties properties = specifier.getReportInterval().getProperties();
        if (properties.getTolerance() != null
                || properties.getXEiNotification() != null
                || properties.getXEiRampUp() != null
                || properties.getXEiRecovery() != null) {
            throw invalidData(
                    "reportInterval must not contain tolerance, notification, rampUp or recovery",
                    payloadRequestId
            );
        }
    }

    private void validateSamplingRange(
            ReportCapability capability,
            Duration granularity,
            Duration reportBackDuration,
            String payloadRequestId
    ) {
        if (reportBackDuration.isZero()) {
            return;
        }

        if (!granularity.isZero()) {
            Duration minimum = Duration.ofSeconds(capability.getMinSamplingPeriodSeconds());
            Duration maximum = Duration.ofSeconds(capability.getMaxSamplingPeriodSeconds());
            if (granularity.compareTo(minimum) < 0
                    || granularity.compareTo(maximum) > 0) {
                throw invalidData(
                        "granularity is outside the offered sampling range",
                        payloadRequestId
                );
            }

            if (granularity.compareTo(reportBackDuration) > 0) {
                throw invalidData(
                        "granularity must not exceed reportBackDuration",
                        payloadRequestId
                );
            }
        }
    }

    private void validateRequestedDuration(
            ReportCapability capability,
            Duration requestedDuration,
            String payloadRequestId
    ) {
        if (requestedDuration == null || requestedDuration.isZero()) {
            return;
        }

        Duration availableDuration = Duration.ofSeconds(capability.getAvailableDurationSeconds());
        if (requestedDuration.compareTo(availableDuration) > 0) {
            throw invalidData(
                    "requested report duration exceeds the available data duration",
                    payloadRequestId
            );
        }
    }

    private Duration requireDuration(String raw, String field, String payloadRequestId) {
        try {
            return OpenAdrTimeUtils.parseOpenAdrDuration(raw)
                    .orElseThrow(() -> invalidData(field + " is required", payloadRequestId));
        } catch (OpenAdrApplicationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalidData(field + " is not a valid ISO-8601 duration", payloadRequestId);
        }
    }

    private void validateNonNegative(
            Duration duration,
            String field,
            String payloadRequestId
    ) {
        if (duration.isNegative()) {
            throw invalidData(field + " must not be negative", payloadRequestId);
        }
    }

    private String requireId(String value, String field, String payloadRequestId) {
        if (value == null || value.isBlank()) {
            throw invalidId(field, "non-blank value", value, payloadRequestId);
        }
        return value;
    }

    private OpenAdrApplicationException invalidId(
            String field,
            String expected,
            String actual,
            String requestId
    ) {
        return new OpenAdrApplicationException(
                "Unexpected OpenADR ID. field=%s, expected=%s, actual=%s"
                        .formatted(field, expected, actual),
                OpenADRResponseCode.INVALID_ID,
                "ID not as expected: " + field,
                requestId
        );
    }

    private OpenAdrApplicationException invalidData(String description, String requestId) {
        return new OpenAdrApplicationException(
                "Invalid OpenADR report request: " + description,
                OpenADRResponseCode.INVALID_DATA,
                description,
                requestId
        );
    }

    private record ReportInterval(Instant start, Duration duration) {
        private static final ReportInterval NONE = new ReportInterval(null, null);
    }
}
