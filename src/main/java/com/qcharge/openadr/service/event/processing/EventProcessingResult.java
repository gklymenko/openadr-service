package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;

public record EventProcessingResult(
        String eventId,
        long modificationNumber,
        int responseCode,
        OptTypeType optType
) {
}
