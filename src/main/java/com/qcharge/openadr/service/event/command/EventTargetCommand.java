package com.qcharge.openadr.service.event.command;

import java.util.List;

/** Event-level targeting data used by the QCharge resource resolver. */
public record EventTargetCommand(
        boolean present,
        List<String> venIds,
        List<String> resourceIds
) {
    public EventTargetCommand {
        venIds = List.copyOf(venIds);
        resourceIds = List.copyOf(resourceIds);
    }

    public static EventTargetCommand absent() {
        return new EventTargetCommand(false, List.of(), List.of());
    }
}
