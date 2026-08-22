package com.qcharge.openadr.eievent;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ErrorHandlingTest {

    // --- Rule 30 ---

    @Test
    void rule30_startafterPresent_randomizesDtstartWithinRange() {
        Instant dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0).toInstant(ZoneOffset.UTC);
        Instant upperBound = dtstart.plusSeconds(300); // PT5M

        Set<Long> offsetsObserved = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Instant result = OpenAdrTimeUtils.applyStartAfterJitter(dtstart, "PT5M");
            assertFalse(result.isBefore(dtstart), "Result must not be before dtstart");
            assertFalse(result.isAfter(upperBound), "Result must not exceed dtstart + startafter");
            offsetsObserved.add(java.time.Duration.between(dtstart, result).getSeconds());
        }
        // With 200 samples over [0,300], probability of seeing only one distinct offset is negligible
        assertTrue(offsetsObserved.size() > 1, "Randomization must produce varied offsets");
    }

    @Test
    void rule30_noStartafter_dtstartUnchanged() {
        Instant dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0).toInstant(ZoneOffset.UTC);

        Instant result = OpenAdrTimeUtils.applyStartAfterJitter(dtstart, null);

        assertEquals(dtstart, result);
    }

    @Test
    void rule30_blankStartafter_dtstartUnchanged() {
        Instant dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0).toInstant(ZoneOffset.UTC);;

        Instant result = OpenAdrTimeUtils.applyStartAfterJitter(dtstart, "  ");

        assertEquals(dtstart, result);
    }

    @Test
    void rule30_zeroStartafter_dtstartUnchanged() {
        Instant dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0).toInstant(ZoneOffset.UTC);

        Instant result = OpenAdrTimeUtils.applyStartAfterJitter(dtstart, "PT0S");

        assertEquals(dtstart, result);
    }

    @Test
    void rule30_startafterBoundary_neverExceedsUpperBound() {
        Instant dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0).toInstant(ZoneOffset.UTC);

        for (int i = 0; i < 500; i++) {
            Instant result = OpenAdrTimeUtils.applyStartAfterJitter(dtstart, "PT3M");
            assertFalse(result.isAfter(dtstart.plusSeconds(180)),
                    "Rule 30: must not exceed dtstart + PT3M");
        }
    }

    // --- ApplicationLayerErrorCodes ---

    @Test
    void applicationLayerErrorCodes_haveCorrectValues() {
        assertEquals(200, OpenADRResponseCode.OK);
        assertEquals(450, OpenADRResponseCode.OUT_OF_SEQUENCE);
        assertEquals(451, OpenADRResponseCode.NOT_ALLOWED);
        assertEquals(452, OpenADRResponseCode.INVALID_ID);
        assertEquals(453, OpenADRResponseCode.NOT_RECOGNIZED);
        assertEquals(454, OpenADRResponseCode.INVALID_DATA);
        assertEquals(459, OpenADRResponseCode.COMPLIANCE_ERROR_OTHER);
        assertEquals(460, OpenADRResponseCode.SIGNAL_NOT_SUPPORTED);
        assertEquals(461, OpenADRResponseCode.REPORT_NOT_SUPPORTED);
        assertEquals(462, OpenADRResponseCode.TARGET_MISMATCH);
        assertEquals(463, OpenADRResponseCode.NOT_REGISTERED);
        assertEquals(469, OpenADRResponseCode.DEPLOYMENT_ERROR_OTHER);
    }

    // --- Application exception ---

    @Test
    void openAdrApplicationException_463_hasCorrectProtocolContext() {
        var ex = new OpenAdrApplicationException(
                "VTN rejected request: 463 Not Registered/Authorized",
                OpenADRResponseCode.NOT_REGISTERED,
                "Not Registered/Authorized",
                "request-123"
        );

        assertEquals(463, ex.getResponseCode());
        assertEquals("Not Registered/Authorized", ex.getResponseDescription());
        assertEquals("request-123", ex.getRequestId());
        assertTrue(ex.getMessage().contains("463"));
    }
}
