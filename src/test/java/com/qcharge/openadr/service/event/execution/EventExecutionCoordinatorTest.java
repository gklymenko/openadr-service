package com.qcharge.openadr.service.event.execution;

import com.qcharge.openadr.model.enums.event.EventCancellationType;
import com.qcharge.openadr.model.enums.event.EventExecutionStatus;
import com.qcharge.openadr.model.enums.event.EventOptType;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.entity.DrEventInterval;
import com.qcharge.openadr.model.entity.DrEventSignal;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.execution.EventExecutionPort.ClearReason;
import com.qcharge.openadr.service.event.store.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventExecutionCoordinatorTest {

    private static final Instant START = Instant.parse("2026-08-11T12:00:00Z");

    @Mock
    private DrEventRepository repository;
    @Mock
    private EventExecutionPort executionPort;
    private EventExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new EventExecutionCoordinator(
                new EventService(repository),
                executionPort,
                new EventTimelineCalculator()
        );
    }

    @Test
    void farEventIsNotAppliedBeforeActualStart() {
        DrEvent event = event(1800L);
        event.setRampUpSeconds(300L);
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        coordinator.processAt(START.minusSeconds(301));

        assertEquals(EventStatus.FAR, event.getVenStatus());
        assertEquals(EventExecutionStatus.SCHEDULED, event.getExecutionStatus());
        verify(executionPort, never()).applyInterval(any());
    }

    @Test
    void transitionsThroughNearAndAppliesEachIntervalOnce() {
        DrEvent event = event(1800L);
        event.setRampUpSeconds(300L);
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        coordinator.processAt(START.minusSeconds(60));
        assertEquals(EventStatus.NEAR, event.getVenStatus());

        coordinator.processAt(START);
        assertEquals(EventStatus.ACTIVE, event.getVenStatus());
        assertEquals(EventExecutionStatus.APPLIED, event.getExecutionStatus());
        assertEquals(0, event.getLastAppliedInterval());
        verify(executionPort).applyInterval(new EventIntervalExecution(
                "event-1", 0, "signal-1", "0", "SIMPLE", "level",
                BigDecimal.ONE, null, null, 0, START));

        coordinator.processAt(START.plusSeconds(100));
        coordinator.processAt(START.plusSeconds(900));
        assertEquals(1, event.getLastAppliedInterval());
        verify(executionPort).applyInterval(new EventIntervalExecution(
                "event-1", 0, "signal-1", "1", "SIMPLE", "level",
                BigDecimal.valueOf(2), null, null, 1, START.plusSeconds(900)));
    }

    @Test
    void completedEventClearsAppliedProfile() {
        DrEvent event = event(1800L);
        event.setExecutionStatus(EventExecutionStatus.APPLIED);
        event.setLastAppliedInterval(1);
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        coordinator.processAt(START.plusSeconds(1800));

        assertEquals(EventStatus.COMPLETED, event.getVenStatus());
        assertEquals(EventExecutionStatus.COMPLETED, event.getExecutionStatus());
        assertEquals(START.plusSeconds(1800), event.getCompletedAt());
        verify(executionPort).clearEvent("event-1", ClearReason.COMPLETED);
    }

    @Test
    void durationZeroKeepsLastIntervalActiveUntilCancellation() {
        DrEvent event = event(0L);
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        coordinator.processAt(START.plusSeconds(3600));

        assertEquals(EventStatus.ACTIVE, event.getVenStatus());
        assertEquals(1, event.getLastAppliedInterval());
        verify(executionPort, never()).clearEvent(any(), any());
    }

    @Test
    void testEventAdvancesLifecycleWithoutOperationalActions() {
        DrEvent event = event(1800L);
        event.setTestEvent(true);
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        coordinator.processAt(START);
        coordinator.processAt(START.plusSeconds(1800));

        assertEquals(EventStatus.COMPLETED, event.getVenStatus());
        assertEquals(EventExecutionStatus.COMPLETED, event.getExecutionStatus());
        assertEquals(0, event.getLastAppliedInterval());
        verify(executionPort, never()).applyInterval(any());
        verify(executionPort, never()).clearEvent(any(), any());
    }

    @Test
    void cancellationPendingKeepsAppliedIntervalUntilEffectiveTime() {
        DrEvent event = event(1800L);
        event.setVenStatus(EventStatus.ACTIVE);
        event.setExecutionStatus(EventExecutionStatus.CANCEL_PENDING);
        event.setLastAppliedInterval(0);
        event.setAppliedAt(START);
        event.setCancellationType(EventCancellationType.IMPLICIT);
        event.setCancellationRequestedAt(START.plusSeconds(100L));
        event.setCancellationEffectiveAt(START.plusSeconds(160L));
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        coordinator.processAt(START.plusSeconds(159L));

        assertEquals(EventStatus.ACTIVE, event.getVenStatus());
        assertEquals(EventExecutionStatus.CANCEL_PENDING, event.getExecutionStatus());
        verify(executionPort, never()).clearEvent(any(), any());
        verify(executionPort, never()).applyInterval(any());
    }

    @Test
    void cancellationPendingIsClearedAndFinalizedAtEffectiveTime() {
        DrEvent event = event(1800L);
        event.setVenStatus(EventStatus.ACTIVE);
        event.setExecutionStatus(EventExecutionStatus.CANCEL_PENDING);
        event.setLastAppliedInterval(0);
        event.setAppliedAt(START);
        event.setCancellationType(EventCancellationType.EXPLICIT);
        event.setCancellationRequestedAt(START.plusSeconds(100L));
        event.setCancellationEffectiveAt(START.plusSeconds(160L));
        when(repository.findAllByExecutionStatusIn(any())).thenReturn(List.of(event));

        coordinator.processAt(START.plusSeconds(160L));

        assertEquals(EventStatus.CANCELLED, event.getVenStatus());
        assertEquals(EventExecutionStatus.CANCELLED, event.getExecutionStatus());
        assertEquals(START.plusSeconds(160L), event.getCompletedAt());
        verify(executionPort).clearEvent(
                "event-1", ClearReason.CANCELLED);
    }

    @Test
    void registrationCancellationClearsAppliedEffectsAndDeletesAllEvents() {
        DrEvent applied = event(1800L);
        applied.setExecutionStatus(EventExecutionStatus.APPLIED);
        applied.setLastAppliedInterval(0);
        applied.setAppliedAt(START);

        DrEvent scheduled = event(1800L);
        scheduled.setEventId("event-2");

        when(repository.findAll()).thenReturn(List.of(applied, scheduled));

        coordinator.clearDownstreamForRegistrationCancellation();

        verify(executionPort).clearEvent(
                "event-1", ClearReason.REGISTRATION_CANCELLED
        );
        verify(executionPort, never()).clearEvent(
                "event-2", ClearReason.REGISTRATION_CANCELLED
        );
    }

    private DrEvent event(long durationSeconds) {
        DrEvent event = new DrEvent();
        event.setEventId("event-1");
        event.setVenStatus(EventStatus.FAR);
        event.setVtnStatus(EventStatus.FAR);
        event.setExecutionStatus(EventExecutionStatus.SCHEDULED);
        event.setOptType(EventOptType.OPT_IN);
        event.setRequestedStartTime(START);
        event.setStartTime(START);
        event.setStartAfterSeconds(0L);
        event.setRandomOffsetSeconds(0L);
        event.setDurationSeconds(durationSeconds);
        event.setLastAppliedInterval(-1);

        DrEventSignal signal = new DrEventSignal();
        signal.setEvent(event);
        signal.setSignalId("signal-1");
        signal.setSignalName("SIMPLE");
        signal.setSignalType("level");
        signal.setSelectedForExecution(true);
        signal.addInterval(interval(0, 900L, BigDecimal.ONE));
        signal.addInterval(interval(1, 900L, BigDecimal.valueOf(2)));
        event.getSignals().add(signal);
        return event;
    }

    private DrEventInterval interval(int sequence, long duration, BigDecimal value) {
        DrEventInterval interval = new DrEventInterval();
        interval.setSequenceNumber(sequence);
        interval.setIntervalUid(Integer.toString(sequence));
        interval.setDurationSeconds(duration);
        interval.setPayloadValue(value);
        return interval;
    }
}
