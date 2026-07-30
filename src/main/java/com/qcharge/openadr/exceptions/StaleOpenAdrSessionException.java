package com.qcharge.openadr.exceptions;

public class StaleOpenAdrSessionException extends IllegalStateException {

    public StaleOpenAdrSessionException(long generation) {
        super("OpenADR session generation is stale: " + generation);
    }
}
