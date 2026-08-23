package com.qcharge.openadr;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LogMessage {
    public static final String SEND_OADR_QUERY_REGISTRATION = "Send optional oadrQueryRegistration. requestId={}";
    public static final String COMPLETED_OADR_QUERY_REGISTRATION = "oadrQueryRegistration completed";

    public static final String START_VEN_BOOTSTRAP = "Starting OpenADR VEN bootstrap. configuredVenId={}";
    public static final String FAILED_VEN_BOOTSTRAP = "OpenADR VEN bootstrap failed";

    public static final String SEND_CREATE_PARTY_REGISTRATION = "Send oadrCreatePartyRegistration. venId={}, requestId={}, reRegistration={}";
    public static final String VEN_NEW_REGISTRATION_COMPLETED = "VEN registration flow completed. venId={}, vtnId={}, " +
            "registrationId={}, pollInterval={}";

    public static final String VEN_REREGISTRATION_COMPLETED = "VEN re-registration flow completed. venId={}, vtnId={}, " +
            "registrationId={}, pollInterval={}";

    public static final String SEND_CANCEL_PARTY_REGISTRATION = "Send oadrCancelPartyRegistration. venId={}, registrationId={}";
    public static final String VEN_REGISTRATION_CANCEL_COMPLETED = "VEN registration cancelled. registrationId={}";

    public static final String FORCE_NEW_REGISTRATION = "Forcing a new registration without registrationID";

    public static final String DUPLICATE_REGISTRATION_REQUEST = "Skipping duplicate OpenADR registration request. " +
            "requestedGeneration={}, currentGeneration={}";

    public static final String REREGISTRATION_FOR_STALE_REGISTRATION_SESSION = "Ignoring re-registration for stale OpenADR session. " +
            "requestedGeneration={}, currentGeneration={}";

    public static final String SESSION_STATE_CHANGED_TO = "OpenADR session state changed from {} to {}, generation={}";

    public static final String PULLED_CANCEL_PARTY_REGISTRATION = "Pulled oadrCancelPartyRegistration for registrationId={}";
    public static final String IGNORE_CANCEL_PARTY_REGISTRATION = "Ignoring oadrCancelPartyRegistration because the VEN is not registered. registrationId={}";
    public static final String INVALID_ID_CANCEL_PARTY_REGISTRATION = "Cannot cancel registration: registrationID does not match the active registration. requested={}";
    public static final String ERROR_CANCEL_PARTY_REGISTRATION = "Ignoring oadrCancelPartyRegistration because the registration is no longer active. registrationId={}";
    public static final String COMPLETED_CANCEL_PARTY_REGISTRATION = "VTN-initiated registration cancellation completed. registrationId={}";

}