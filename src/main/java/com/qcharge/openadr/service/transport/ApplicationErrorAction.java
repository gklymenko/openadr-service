package com.qcharge.openadr.service.transport;

/**
 * State-machine action associated with an inbound OpenADR application error.
 */
public enum ApplicationErrorAction {
    FAIL_OPERATION,
    REQUIRE_REREGISTRATION,
    STOP_POLLING
}
