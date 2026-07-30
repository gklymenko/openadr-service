package com.qcharge.openadr.exceptions;

import com.qcharge.openadr.service.session.OpenAdrSessionState;

public class OpenAdrSessionUnavailableException extends IllegalStateException {

    public OpenAdrSessionUnavailableException(OpenAdrSessionState state) {
        super("OpenADR registered session is unavailable. state=" + state);
    }
}
