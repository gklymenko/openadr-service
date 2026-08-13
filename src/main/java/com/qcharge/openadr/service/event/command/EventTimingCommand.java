package com.qcharge.openadr.service.event.command;

import java.time.Instant;

/** Normalized active-period data; all XML duration parsing is completed at the boundary. */
public record EventTimingCommand(
        Instant requestedStartTime,
        long startAfterSeconds,
        long durationSeconds,
        Long rampUpSeconds,
        Long recoverySeconds
) {
}
