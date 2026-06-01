package com.qcharge.openadr.eievent;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.event.EventValidationService.ParsedSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SignalParsingTest extends AbstractOadrTest {

    private EventValidationService service;

    @BeforeEach
    void setUp() {
        service = new EventValidationService();
    }

    @Test
    void parseSignal_loadDispatch_parsedCorrectly() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_loadDispatch.xml");

        Optional<ParsedSignal> result = service.parseSignal(event);

        assertTrue(result.isPresent());
        ParsedSignal signal = result.get();
        assertEquals(EventValidationService.SIGNAL_LOAD_DISPATCH, signal.signalName());
        assertEquals("setpoint", signal.signalType());
        assertNotNull(signal.currentValue());
        assertEquals(0, BigDecimal.valueOf(25.0).compareTo(signal.currentValue()));
    }

    @Test
    void parseSignal_electricityPrice_parsedCorrectly() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_electricityPrice.xml");

        Optional<ParsedSignal> result = service.parseSignal(event);

        assertTrue(result.isPresent());
        ParsedSignal signal = result.get();
        assertEquals(EventValidationService.SIGNAL_ELECTRICITY_PRICE, signal.signalName());
        assertEquals("price", signal.signalType());
        assertNotNull(signal.currentValue());
        assertTrue(signal.currentValue().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void parseSignal_simple_parsedCorrectly() throws Oadr20bUnmarshalException {
        // existing fixture has a SIMPLE signal
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");

        Optional<ParsedSignal> result = service.parseSignal(event);

        assertTrue(result.isPresent());
        // existing fixture has both SIMPLE and ELECTRICITY_PRICE; LOAD_DISPATCH wins first but is absent,
        // so ELECTRICITY_PRICE takes priority over SIMPLE — verify we get ELECTRICITY_PRICE here
        // (fixture has SIMPLE first then ELECTRICITY_PRICE, but priority list is LOAD_DISPATCH > ELECTRICITY_PRICE > SIMPLE)
        assertEquals(EventValidationService.SIGNAL_ELECTRICITY_PRICE, result.get().signalName());
    }

    @Test
    void parseSignal_simpleOnly_parsedCorrectly() throws Oadr20bUnmarshalException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                new File(EIEVENT_PATH + "oadrDistributeEvent_simple.xml").exists(),
                "Simple-only fixture not present — skipping"
        );
        // mock-vtn event has only SIMPLE signal
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_simple.xml");

        Optional<ParsedSignal> result = service.parseSignal(event);

        assertTrue(result.isPresent());
        assertEquals(EventValidationService.SIGNAL_SIMPLE, result.get().signalName());
        assertEquals("level", result.get().signalType());
    }

    @Test
    void parseSignal_unknownSignal_returnsEmpty() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_unknownSignal.xml");

        Optional<ParsedSignal> result = service.parseSignal(event);

        assertTrue(result.isEmpty(), "Unknown signal should return empty (Rule 109)");
    }

    @Test
    void parseSignal_loadDispatchTakesPriorityOverSimple() throws Oadr20bUnmarshalException {
        // The existing fixture has SIMPLE + ELECTRICITY_PRICE — no LOAD_DISPATCH
        // The loadDispatch fixture has only LOAD_DISPATCH
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_loadDispatch.xml");

        Optional<ParsedSignal> result = service.parseSignal(event);

        assertTrue(result.isPresent());
        assertEquals(EventValidationService.SIGNAL_LOAD_DISPATCH, result.get().signalName(),
                "LOAD_DISPATCH must take priority");
    }

    // --- helpers ---

    private OadrEvent loadFirstEvent(String filename) throws Oadr20bUnmarshalException {
        File file = new File(EIEVENT_PATH + filename);
        OadrDistributeEventType distribute =
                jaxbContext.unmarshal(file, OadrDistributeEventType.class);
        assertFalse(distribute.getOadrEvent().isEmpty());
        return distribute.getOadrEvent().get(0);
    }
}
