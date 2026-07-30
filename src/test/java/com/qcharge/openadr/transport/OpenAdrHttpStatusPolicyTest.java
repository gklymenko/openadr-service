package com.qcharge.openadr.transport;

import com.qcharge.openadr.service.transport.HttpStatusAction;
import com.qcharge.openadr.service.transport.OpenAdrHttpStatusPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAdrHttpStatusPolicyTest {

    private final OpenAdrHttpStatusPolicy policy = new OpenAdrHttpStatusPolicy();

    @ParameterizedTest
    @CsvSource({
            "200, ACCEPT",
            "404, DO_NOT_RETRY",
            "406, DO_NOT_RETRY",
            "500, RETRY_AFTER_QUIESCE",
            "501, DO_NOT_RETRY",
            "503, RETRY_AFTER_QUIESCE"
    })
    void classify_openAdrDefinedStatus_returnsExpectedAction(
            int httpStatusCode,
            HttpStatusAction expected
    ) {
        assertEquals(expected, policy.classify(httpStatusCode));
    }

    @ParameterizedTest
    @CsvSource({
            "201, DO_NOT_RETRY",
            "302, DO_NOT_RETRY",
            "400, DO_NOT_RETRY",
            "429, DO_NOT_RETRY",
            "502, RETRY_AFTER_QUIESCE",
            "504, RETRY_AFTER_QUIESCE"
    })
    void classify_fallbackStatus_returnsSafeAction(
            int httpStatusCode,
            HttpStatusAction expected
    ) {
        assertEquals(expected, policy.classify(httpStatusCode));
    }

    @Test
    void classify_noHttpResponse_retriesAfterQuiesce() {
        assertEquals(
                HttpStatusAction.RETRY_AFTER_QUIESCE,
                policy.classify(null)
        );
    }
}
