package com.qcharge.openadr.service.session;

public enum OpenAdrSessionState {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    REREGISTERING,
    CANCELLING,
    CANCELLED,
    FAILED
}
