package com.qcharge.openadr.service.event.command;

import java.util.List;

/** Complete application command produced from one OpenADR oadrEvent. */
public record ReceiveEventCommand(
        String eventId,
        long modificationNumber,
        EventStatus status,
        Integer priority,
        boolean testEvent,
        String marketContext,
        EventTimingCommand timing,
        List<EventSignalCommand> signals,
        EventTargetCommand target
) {
    public ReceiveEventCommand {
        signals = List.copyOf(signals);
    }
}
