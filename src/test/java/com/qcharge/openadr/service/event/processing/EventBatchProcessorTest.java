package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.model.enums.event.EventOptType;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.service.event.command.ReceiveEventCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static com.qcharge.openadr.exceptions.OpenADRResponseCode.OK;
import static com.qcharge.openadr.exceptions.OpenADRResponseCode.TARGET_MISMATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventBatchProcessorTest {

    @Mock
    private EventProcessor eventProcessor;

    @Mock
    private EventCancellationService cancellationService;

    private EventBatchProcessor batchProcessor;

    @BeforeEach
    void setUp() {
        batchProcessor = new EventBatchProcessor(eventProcessor, cancellationService);
    }

    @Test
    void expectedVtnErrorDoesNotPreventSiblingProcessingOrSnapshotReconciliation() {
        ReceiveEventCommand invalid = event("EVENT-1");
        ReceiveEventCommand valid = event("EVENT-2");
        EventProcessingResult rejected = result("EVENT-1", TARGET_MISMATCH);
        EventProcessingResult accepted = result("EVENT-2", OK);
        Set<String> snapshotIds = Set.of("EVENT-1", "EVENT-2");

        when(eventProcessor.process(invalid, "VEN-1")).thenReturn(rejected);
        when(eventProcessor.process(valid, "VEN-1")).thenReturn(accepted);

        List<EventProcessingResult> results = batchProcessor.process(
                List.of(invalid, valid), snapshotIds, "VEN-1"
        );

        assertEquals(List.of(rejected, accepted), results);
        InOrder order = inOrder(eventProcessor, cancellationService);
        order.verify(eventProcessor).process(invalid, "VEN-1");
        order.verify(eventProcessor).process(valid, "VEN-1");
        order.verify(cancellationService).reconcileSnapshot(snapshotIds);
    }

    @Test
    void internalFailureEscapesAndPreventsSnapshotReconciliation() {
        ReceiveEventCommand first = event("EVENT-1");
        ReceiveEventCommand failing = event("EVENT-2");
        Set<String> snapshotIds = Set.of("EVENT-1", "EVENT-2");

        when(eventProcessor.process(first, "VEN-1")).thenReturn(result("EVENT-1", OK));
        when(eventProcessor.process(failing, "VEN-1"))
                .thenThrow(new IllegalStateException("Database write failed"));

        assertThrows(
                IllegalStateException.class,
                () -> batchProcessor.process(List.of(first, failing), snapshotIds, "VEN-1")
        );

        verify(cancellationService, never()).reconcileSnapshot(snapshotIds);
    }

    private ReceiveEventCommand event(String eventId) {
        return new ReceiveEventCommand(
                eventId,
                0L,
                EventStatus.FAR,
                0,
                false,
                "http://market-context",
                null,
                List.of(),
                null
        );
    }

    private EventProcessingResult result(String eventId, int responseCode) {
        return new EventProcessingResult(
                eventId,
                0L,
                responseCode,
                responseCode == OK ? EventOptType.OPT_IN : EventOptType.OPT_OUT
        );
    }
}
