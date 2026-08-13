package com.qcharge.openadr.service.event.command;

import java.math.BigDecimal;

/** One normalized signal interval, independent of JAXB stream payload wrappers. */
public record EventIntervalCommand(
        String uid,
        int sequenceNumber,
        long durationSeconds,
        BigDecimal payloadValue,
        boolean explicitStart
) {
}
