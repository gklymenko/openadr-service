package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** Persists one complete VTN event snapshot atomically after protocol validation. */
@Service
@RequiredArgsConstructor
public class EventBatchProcessor {

    private final EventProcessor eventProcessor;
    private final EventCancellationService cancellationService;

    @Transactional
    public List<EventProcessingResult> process(
            List<ReceiveEventCommand> events,
            Set<String> receivedEventIds,
            String venId
    ) {
        List<EventProcessingResult> results = events.stream()
                .map(event -> eventProcessor.process(event, venId))
                .toList();

        // Rule 61: reconcile only after every valid event in the complete snapshot was applied.
        cancellationService.reconcileSnapshot(receivedEventIds);
        return results;
    }
}
