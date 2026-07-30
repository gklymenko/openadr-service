package com.qcharge.openadr.service.event;

import lombok.Getter;

@Getter
public class EventValidationException extends RuntimeException {

    private final int responseCode;

    public EventValidationException(String message, int responseCode) {
        super(message);
        this.responseCode = responseCode;
    }
}
