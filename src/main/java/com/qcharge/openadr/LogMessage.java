package com.qcharge.openadr;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LogMessage {
    public static final String START_VEN_BOOTSTRAP = "Starting OpenADR VEN bootstrap. configuredVenId={}";
    public static final String FAILED_VEN_BOOTSTRAP = "OpenADR VEN bootstrap failed";

    public static final String DUPLICATE_REGISTRATION_REQUEST  = "Skipping duplicate OpenADR registration request. " +
            "requestedGeneration={}, currentGeneration={}";

    public static final String REREGISTRATION_FOR_STALE_REGISTRATION_SESSION = "Ignoring re-registration for stale OpenADR session. " +
            "requestedGeneration={}, currentGeneration={}";

    public static final String SESSION_STATE_CHANGED_TO = "OpenADR session state changed from {} to {}, generation={}";


}