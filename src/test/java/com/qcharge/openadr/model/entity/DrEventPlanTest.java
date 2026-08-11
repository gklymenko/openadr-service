package com.qcharge.openadr.model.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrEventPlanTest {

    @Test
    void replaceSignals_setsBothRelationshipSidesAndRemovesOldPlan() {
        DrEvent event = new DrEvent();
        DrEventSignal oldSignal = signal("old");
        event.replaceSignals(List.of(oldSignal));

        DrEventSignal replacement = signal("new");
        DrEventInterval interval = new DrEventInterval();
        replacement.addInterval(interval);
        event.replaceSignals(List.of(replacement));

        assertEquals(List.of(replacement), event.getSignals());
        assertSame(event, replacement.getEvent());
        assertSame(replacement, interval.getSignal());
        assertTrue(event.getSignals().stream().noneMatch(signal -> "old".equals(signal.getSignalId())));
    }

    private DrEventSignal signal(String signalId) {
        DrEventSignal signal = new DrEventSignal();
        signal.setSignalId(signalId);
        return signal;
    }
}
