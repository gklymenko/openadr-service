package com.qcharge.openadr.integration.central.kafka;

public enum IngestionOutcome {
    APPLIED,
    STALE_OR_DUPLICATE,
    UNKNOWN_RESOURCE
}
