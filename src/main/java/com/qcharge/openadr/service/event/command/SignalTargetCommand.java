package com.qcharge.openadr.service.event.command;

import java.util.List;

/** Signal-level device-class target; null on a signal means that the target was omitted. */
public record SignalTargetCommand(
        boolean hasNonDeviceClassTarget,
        List<String> endDeviceClasses
) {
    public SignalTargetCommand {
        endDeviceClasses = List.copyOf(endDeviceClasses);
    }
}
