package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.model.enums.event.EventOptType;

public record EventProcessingResult(
        String eventId,
        long modificationNumber,
        int responseCode,
        EventOptType optType
) {
}
