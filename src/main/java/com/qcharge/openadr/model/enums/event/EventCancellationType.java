package com.qcharge.openadr.model.enums.event;

//an internal classification of how the VEN learned that an event was cancelled. It is not an OpenADR/JAXB enum.
public enum EventCancellationType {
    // VTN sends the known event again with eventStatus=CANCELLED, normally with a higher modificationNumber,
    // VEN should send acknowledgment to VTN
    EXPLICIT,

    //An authoritative oadrDistributeEvent snapshot omitted an event previously known to the VEN—Rule 61 implied cancellation
    //VEN should NOT send acknowledgment to VTN
    IMPLICIT
}
