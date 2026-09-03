package com.qcharge.openadr.service.report;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.entity.ReportCapability;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.ei.ReadingTypeEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.repository.ReportCapabilityRepository;
import com.qcharge.openadr.repository.ReportRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportRequestValidatorTest {

    private static final String PAYLOAD_REQUEST_ID = "PAYLOAD-1";
    private static final String REPORT_SPECIFIER_ID = "SPEC-1";
    private static final String RID = "RID-1";

    @Mock ReportCapabilityRepository capabilityRepository;
    @Mock ReportRequestRepository requestRepository;

    private ReportRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ReportRequestValidator(capabilityRepository, requestRepository);
        when(requestRepository.existsByReportRequestId(anyString())).thenReturn(false);
    }

    @Test
    void acceptsPeriodicRequestWithinAdvertisedCapability() {
        when(capabilityRepository.findByReportSpecifierId(REPORT_SPECIFIER_ID))
                .thenReturn(Optional.of(capability()));

        List<ValidatedReportRequest> result = validator.validateAll(
                List.of(request("REPORT-1", REPORT_SPECIFIER_ID, RID, "PT1M", "PT2M", "PT10M")),
                PAYLOAD_REQUEST_ID
        );

        assertEquals(1, result.size());
        assertEquals(REPORT_SPECIFIER_ID, result.getFirst().reportSpecifierId());
        assertEquals("RESOURCE-1", result.getFirst().resourceId());
        assertTrue(result.getFirst().requestedRids().contains(RID));
    }

    @Test
    void returns452ForUnknownReportSpecifierId() {
        when(capabilityRepository.findByReportSpecifierId("UNKNOWN")).thenReturn(Optional.empty());

        assertResponseCode(
                OpenADRResponseCode.INVALID_ID,
                request("REPORT-1", "UNKNOWN", RID, "PT1M", "PT0S", null)
        );
    }

    @Test
    void returns452ForRidNotOfferedByCapability() {
        when(capabilityRepository.findByReportSpecifierId(REPORT_SPECIFIER_ID))
                .thenReturn(Optional.of(capability()));

        assertResponseCode(
                OpenADRResponseCode.INVALID_ID,
                request("REPORT-1", REPORT_SPECIFIER_ID, "UNKNOWN-RID", "PT1M", "PT0S", null)
        );
    }

    @Test
    void returns452ForDuplicateReportRequestId() {
        OadrReportRequestType first = request(
                "REPORT-1", REPORT_SPECIFIER_ID, RID, "PT1M", "PT0S", null
        );
        OadrReportRequestType second = request(
                "REPORT-1", REPORT_SPECIFIER_ID, RID, "PT1M", "PT0S", null
        );
        when(capabilityRepository.findByReportSpecifierId(REPORT_SPECIFIER_ID))
                .thenReturn(Optional.of(capability()));

        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validateAll(List.of(first, second), PAYLOAD_REQUEST_ID)
        );

        assertEquals(OpenADRResponseCode.INVALID_ID, exception.getResponseCode());
    }

    @Test
    void returns452UnlessMetadataRidIsZero() {
        assertResponseCode(
                OpenADRResponseCode.INVALID_ID,
                request("REPORT-1", "METADATA", RID, "PT0S", "PT0S", null)
        );
    }

    @Test
    void returns454ForInvalidReadingType() {
        OadrReportRequestType request = request(
                "REPORT-1", REPORT_SPECIFIER_ID, RID, "PT1M", "PT0S", null
        );
        request.getReportSpecifier().getSpecifierPayload().getFirst()
                .setReadingType(ReadingTypeEnumeratedType.DIRECT_READ.value());

        assertResponseCode(OpenADRResponseCode.INVALID_DATA, request);
    }

    @Test
    void returns454WhenPeriodicRequestHasNoReportInterval() {
        assertResponseCode(
                OpenADRResponseCode.INVALID_DATA,
                request("REPORT-1", REPORT_SPECIFIER_ID, RID, "PT1M", "PT2M", null)
        );
    }

    @Test
    void returns454ForGranularityOutsideAdvertisedRange() {
        when(capabilityRepository.findByReportSpecifierId(REPORT_SPECIFIER_ID))
                .thenReturn(Optional.of(capability()));

        assertResponseCode(
                OpenADRResponseCode.INVALID_DATA,
                request("REPORT-1", REPORT_SPECIFIER_ID, RID, "PT5S", "PT2M", "PT10M")
        );
    }

    @Test
    void returns454WhenRequestedDurationExceedsAvailableDuration() {
        when(capabilityRepository.findByReportSpecifierId(REPORT_SPECIFIER_ID))
                .thenReturn(Optional.of(capability()));

        assertResponseCode(
                OpenADRResponseCode.INVALID_DATA,
                request("REPORT-1", REPORT_SPECIFIER_ID, RID, "PT1M", "PT2M", "PT61M")
        );
    }

    @Test
    void ignoresReportIntervalForOneShotTelemetryRequest() {
        when(capabilityRepository.findByReportSpecifierId(REPORT_SPECIFIER_ID))
                .thenReturn(Optional.of(capability()));

        List<ValidatedReportRequest> result = validator.validateAll(
                List.of(request(
                        "REPORT-1",
                        REPORT_SPECIFIER_ID,
                        RID,
                        "PT1M",
                        "PT0S",
                        "PT61M"
                )),
                PAYLOAD_REQUEST_ID
        );

        assertEquals(1, result.size());
        assertNull(result.getFirst().requestedStart());
        assertNull(result.getFirst().requestedDuration());
    }

    private void assertResponseCode(int responseCode, OadrReportRequestType request) {
        OpenAdrApplicationException exception = assertThrows(
                OpenAdrApplicationException.class,
                () -> validator.validateAll(List.of(request), PAYLOAD_REQUEST_ID)
        );

        assertEquals(responseCode, exception.getResponseCode());
        assertEquals(PAYLOAD_REQUEST_ID, exception.getRequestId());
    }

    private OadrReportRequestType request(
            String reportRequestId,
            String reportSpecifierId,
            String rid,
            String granularity,
            String reportBackDuration,
            String reportIntervalDuration
    ) {
        OadrReportRequestType request = Oadr20bFactory.createOadrReportRequestType(
                reportRequestId,
                reportSpecifierId,
                granularity,
                reportBackDuration
        );
        request.getReportSpecifier().getSpecifierPayload().add(
                Oadr20bFactory.createSpecifierPayloadType(
                        null,
                        ReadingTypeEnumeratedType.X_NOT_APPLICABLE,
                        rid
                )
        );

        if (reportIntervalDuration != null) {
            request.getReportSpecifier().setReportInterval(
                    Oadr20bFactory.createWsCalendarIntervalType(
                            Instant.now().toEpochMilli(),
                            reportIntervalDuration
                    )
            );
        }

        return request;
    }

    private ReportCapability capability() {
        ReportCapability capability = new ReportCapability();
        capability.setReportSpecifierId(REPORT_SPECIFIER_ID);
        capability.setReportName("TELEMETRY_USAGE");
        capability.setResourceId("RESOURCE-1");
        capability.setSupportedRids(RID);
        capability.setMinSamplingPeriodSeconds(10);
        capability.setMaxSamplingPeriodSeconds(60);
        capability.setAvailableDurationSeconds(60 * 60);
        return capability;
    }
}
