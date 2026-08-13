package com.qcharge.openadr.service.event.processing;

import com.qcharge.openadr.model.entity.DrEvent;
import org.springframework.stereotype.Component;

/** OpenADR modification-number policy, kept separate from persistence and protocol responses. */
@Component
public class EventVersionPolicy {

    public State evaluate(DrEvent storedEvent, long receivedModificationNumber) {
        if (storedEvent == null) {
            return State.NEW;
        }
        int storedModificationNumber = storedEvent.getModificationNumber();
        if (receivedModificationNumber == storedModificationNumber) {
            return State.DUPLICATE;
        }
        if (receivedModificationNumber > storedModificationNumber) {
            return State.MODIFIED;
        }
        return State.OUT_OF_SEQUENCE;
    }

    public enum State {
        NEW,
        MODIFIED,
        DUPLICATE,
        OUT_OF_SEQUENCE
    }
}
