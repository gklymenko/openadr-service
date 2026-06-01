package com.qcharge.openadr.eievent;

import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrTransportException;
import com.qcharge.openadr.service.event.DrEventHandler;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ErrorHandlingTest {

    // --- Rule 30 ---

    @Test
    void rule30_startafterPresent_randomizesDtstartWithinRange() {
        LocalDateTime dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0);
        LocalDateTime upperBound = dtstart.plusSeconds(300); // PT5M

        Set<Long> offsetsObserved = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            LocalDateTime result = DrEventHandler.applyStartAfterJitter(dtstart, "PT5M");
            assertFalse(result.isBefore(dtstart), "Result must not be before dtstart");
            assertFalse(result.isAfter(upperBound), "Result must not exceed dtstart + startafter");
            offsetsObserved.add(java.time.Duration.between(dtstart, result).getSeconds());
        }
        // With 200 samples over [0,300], probability of seeing only one distinct offset is negligible
        assertTrue(offsetsObserved.size() > 1, "Randomization must produce varied offsets");
    }

    @Test
    void rule30_noStartafter_dtstartUnchanged() {
        LocalDateTime dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0);

        LocalDateTime result = DrEventHandler.applyStartAfterJitter(dtstart, null);

        assertEquals(dtstart, result);
    }

    @Test
    void rule30_blankStartafter_dtstartUnchanged() {
        LocalDateTime dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0);

        LocalDateTime result = DrEventHandler.applyStartAfterJitter(dtstart, "  ");

        assertEquals(dtstart, result);
    }

    @Test
    void rule30_zeroStartafter_dtstartUnchanged() {
        LocalDateTime dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0);

        LocalDateTime result = DrEventHandler.applyStartAfterJitter(dtstart, "PT0S");

        assertEquals(dtstart, result);
    }

    @Test
    void rule30_startafterBoundary_neverExceedsUpperBound() {
        LocalDateTime dtstart = LocalDateTime.of(2026, 6, 1, 10, 0, 0);

        for (int i = 0; i < 500; i++) {
            LocalDateTime result = DrEventHandler.applyStartAfterJitter(dtstart, "PT3M");
            assertFalse(result.isAfter(dtstart.plusSeconds(180)),
                    "Rule 30: must not exceed dtstart + PT3M");
        }
    }

    // --- ApplicationLayerErrorCodes ---

    @Test
    void applicationLayerErrorCodes_haveCorrectValues() {
        assertEquals(200, ApplicationLayerErrorCodes.OK);
        assertEquals(450, ApplicationLayerErrorCodes.OUT_OF_SEQUENCE);
        assertEquals(451, ApplicationLayerErrorCodes.NOT_ALLOWED);
        assertEquals(452, ApplicationLayerErrorCodes.INVALID_ID);
        assertEquals(453, ApplicationLayerErrorCodes.NOT_RECOGNIZED);
        assertEquals(454, ApplicationLayerErrorCodes.INVALID_DATA);
        assertEquals(459, ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER);
        assertEquals(460, ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED);
        assertEquals(461, ApplicationLayerErrorCodes.REPORT_NOT_SUPPORTED);
        assertEquals(462, ApplicationLayerErrorCodes.TARGET_MISMATCH);
        assertEquals(463, ApplicationLayerErrorCodes.NOT_REGISTERED);
        assertEquals(469, ApplicationLayerErrorCodes.DEPLOYMENT_ERROR_OTHER);
    }

    // --- 463 exception ---

    @Test
    void openAdrTransportException_463_hasCorrectStatusCode() {
        var ex = new OpenAdrTransportException(
                "VTN rejected request: 463 Not Registered/Authorized",
                ApplicationLayerErrorCodes.NOT_REGISTERED,
                null);

        assertEquals(463, ex.getHttpStatusCode());
        assertTrue(ex.getMessage().contains("463"));
        // 463 is in 4xx range — isClientError() returns true
        assertTrue(ex.isClientError());
        assertFalse(ex.isServerError());
    }

    @Test
    void openAdrTransportException_463_isClientError_notRetried() {
        // Verify the RetryHandler would not retry a 463
        var ex = new OpenAdrTransportException("463", 463, null);
        assertTrue(ex.isClientError(), "463 must be treated as client error (no retry)");
    }
}
