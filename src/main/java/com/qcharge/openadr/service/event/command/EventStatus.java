package com.qcharge.openadr.service.event.command;

/** Protocol-independent lifecycle status received from the VTN. */
public enum EventStatus {
    FAR,
    NEAR,
    ACTIVE,
    COMPLETED,
    CANCELLED
}
