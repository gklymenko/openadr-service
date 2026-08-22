package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import org.springframework.stereotype.Component;

/**
 * Maps an inbound OpenADR application response code to the VEN state action.
 *
 * <p>Application errors are logical protocol responses. They never enter the
 * HTTP retry policy.
 */
@Component
public class OpenAdrApplicationErrorPolicy {

    public ApplicationErrorAction classify(
            OpenAdrOperation<?, ?> operation,
            int responseCode
    ) {
        if (responseCode == OpenADRResponseCode.NOT_REGISTERED) {
            return ApplicationErrorAction.REQUIRE_REREGISTRATION;
        }

        return ApplicationErrorAction.FAIL_OPERATION;
    }
}
