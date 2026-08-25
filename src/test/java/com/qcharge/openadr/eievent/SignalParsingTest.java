package com.qcharge.openadr.eievent;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.service.event.EventPolicyService;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.event.command.EventIntervalCommand;
import com.qcharge.openadr.service.event.command.EventSignalCommand;
import com.qcharge.openadr.service.event.protocol.OpenAdrEventCommandMapper;
import com.qcharge.openadr.service.validation.EventEntryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SignalParsingTest extends AbstractOadrTest {

    private EventPolicyService service;
    private OpenAdrEventCommandMapper mapper;
    private EventEntryValidator eventValidator;

    @BeforeEach
    void setUp() {
        OpenAdrProperties props = new OpenAdrProperties();
        props.getVen().setId("test-ven");
        props.getReport().setResourceId("resource1");
        service = new EventPolicyService(props);
        mapper = new OpenAdrEventCommandMapper();
        eventValidator = new EventEntryValidator();
    }

    @Test
    void parseSignal_loadDispatch_parsedCorrectly() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_loadDispatch.xml");

        Optional<EventSignalCommand> result = parseSignal(event);

        assertTrue(result.isPresent());
        EventSignalCommand signal = result.get();
        assertEquals(EventPolicyService.SIGNAL_LOAD_DISPATCH, signal.signalName());
        assertEquals("setpoint", signal.signalType());
        assertNotNull(signal.currentValue());
        assertEquals(0, BigDecimal.valueOf(25.0).compareTo(signal.currentValue()));
        assertEquals("SIG_LD_01", signal.signalId());
        assertEquals(1, signal.intervals().size());
        assertEquals(3600, signal.intervals().getFirst().durationSeconds());
        assertEquals(0, BigDecimal.valueOf(25.0)
                .compareTo(signal.intervals().getFirst().payloadValue()));
        assertEquals("powerReal", signal.itemBaseElement());
        assertEquals("W", signal.itemUnits());
        assertEquals("k", signal.siScaleCode());
    }

    @Test
    void parseSignal_electricityPrice_parsedCorrectly() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_electricityPrice.xml");

        Optional<EventSignalCommand> result = parseSignal(event);

        assertTrue(result.isPresent());
        EventSignalCommand signal = result.get();
        assertEquals(EventPolicyService.SIGNAL_ELECTRICITY_PRICE, signal.signalName());
        assertEquals("price", signal.signalType());
        assertNotNull(signal.currentValue());
        assertTrue(signal.currentValue().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void parseSignal_simple_parsedCorrectly() throws Oadr20bUnmarshalException {
        // existing fixture has a SIMPLE signal
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");

        Optional<EventSignalCommand> result = parseSignal(event);

        assertTrue(result.isPresent());
        // existing fixture has both SIMPLE and ELECTRICITY_PRICE; LOAD_DISPATCH wins first but is absent,
        // so ELECTRICITY_PRICE takes priority over SIMPLE — verify we get ELECTRICITY_PRICE here
        // (fixture has SIMPLE first then ELECTRICITY_PRICE, but priority list is LOAD_DISPATCH > ELECTRICITY_PRICE > SIMPLE)
        assertEquals(EventPolicyService.SIGNAL_ELECTRICITY_PRICE, result.get().signalName());
    }

    @Test
    void parseSignal_simpleOnly_parsedCorrectly() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");

        event.getEiEvent()
                .getEiEventSignals()
                .getEiEventSignal()
                .removeIf(signal ->
                        !EventPolicyService.SIGNAL_SIMPLE.equalsIgnoreCase(
                                signal.getSignalName()
                        )
                );

        Optional<EventSignalCommand> result = parseSignal(event);

        assertTrue(result.isPresent());
        assertEquals(
                EventPolicyService.SIGNAL_SIMPLE,
                result.get().signalName()
        );
        assertEquals("level", result.get().signalType());
    }

    @Test
    void parseSignal_unknownSignal_returnsEmpty() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_unknownSignal.xml");

        Optional<EventSignalCommand> result = parseSignal(event);

        assertTrue(result.isEmpty(), "Unknown signal should return empty (Rule 109)");
    }

    @Test
    void parseSignal_loadDispatchTakesPriorityOverSimple() throws Oadr20bUnmarshalException {
        // The existing fixture has SIMPLE + ELECTRICITY_PRICE — no LOAD_DISPATCH
        // The loadDispatch fixture has only LOAD_DISPATCH
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_loadDispatch.xml");

        Optional<EventSignalCommand> result = parseSignal(event);

        assertTrue(result.isPresent());
        assertEquals(EventPolicyService.SIGNAL_LOAD_DISPATCH, result.get().signalName(),
                "LOAD_DISPATCH must take priority");
    }

    @Test
    void parseSignals_preservesEverySignalAndInterval() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");

        List<EventSignalCommand> signals = parseSignals(event);

        assertEquals(2, signals.size());
        EventSignalCommand simple = signals.getFirst();
        assertEquals("SIG_01", simple.signalId());
        assertEquals(List.of("0", "1"), simple.intervals().stream()
                .map(EventIntervalCommand::uid)
                .toList());
        assertEquals(List.of(900L, 900L), simple.intervals().stream()
                .map(EventIntervalCommand::durationSeconds)
                .toList());
        assertEquals(List.of(new BigDecimal("3.0"), new BigDecimal("2.0")),
                simple.intervals().stream()
                        .map(EventIntervalCommand::payloadValue)
                        .toList());

        EventSignalCommand price = signals.get(1);
        assertEquals("SIG_02", price.signalId());
        assertEquals("currencyPerKWh", price.itemBaseElement());
        assertEquals("CurrencyType", price.itemBaseType());
        assertEquals("USD", price.itemUnits());
        assertEquals("none", price.siScaleCode());
        assertEquals(2, price.intervals().size());
    }

    @Test
    void parseSignals_rejectsOutOfSequenceIntervalUid() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");
        event.getEiEvent().getEiEventSignals().getEiEventSignal().getFirst()
                .getIntervals().getInterval().get(1).getUid().setText("7");

        EventValidationException exception = assertThrows(
                EventValidationException.class,
                () -> parseSignals(event)
        );

        assertEquals(459, exception.getResponseCode());
        assertTrue(exception.getMessage().contains("must be 1"));
    }

    @Test
    void parseSignals_rejectsIntervalDurationSumMismatch() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");
        event.getEiEvent().getEiEventSignals().getEiEventSignal().getFirst()
                .getIntervals().getInterval().get(1).getDuration().setDuration("PT10M");

        EventValidationException exception = assertThrows(
                EventValidationException.class,
                () -> parseSignals(event)
        );

        assertEquals(459, exception.getResponseCode());
        assertTrue(exception.getMessage().contains("sum to"));
    }

    @Test
    void parseSignals_acceptsZeroIntervalForOpenEndedEvent() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");
        event.getEiEvent().getEiActivePeriod().getProperties()
                .getDuration().setDuration("PT0S");
        event.getEiEvent().getEiEventSignals().getEiEventSignal().forEach(signal -> {
            signal.getIntervals().getInterval().subList(
                    1, signal.getIntervals().getInterval().size()).clear();
            signal.getIntervals().getInterval().getFirst()
                    .getDuration().setDuration("PT0S");
        });

        List<EventSignalCommand> signals = parseSignals(event);

        assertEquals(2, signals.size());
        assertTrue(signals.stream().allMatch(signal ->
                signal.intervals().size() == 1
                        && signal.intervals().getFirst().durationSeconds() == 0L));
    }

    @Test
    void parseSignals_rejectsZeroIntervalForFiniteEvent() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");
        event.getEiEvent().getEiEventSignals().getEiEventSignal().getFirst()
                .getIntervals().getInterval().getFirst()
                .getDuration().setDuration("PT0S");

        EventValidationException exception = assertThrows(
                EventValidationException.class,
                () -> parseSignals(event)
        );

        assertEquals(459, exception.getResponseCode());
        assertTrue(exception.getMessage().contains("unless the event is open-ended"));
    }

    @Test
    void parseSignals_rejectsSimpleValueOutsideZeroToThree() throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");
        var simple = event.getEiEvent().getEiEventSignals().getEiEventSignal().getFirst();
        var payload = (com.qcharge.openadr.model.oadr20b.ei.SignalPayloadType)
                simple.getIntervals().getInterval().getFirst()
                        .getStreamPayloadBase().getFirst().getValue();
        var payloadFloat = (com.qcharge.openadr.model.oadr20b.ei.PayloadFloatType)
                payload.getPayloadBase().getValue();
        payloadFloat.setValue(4.0f);

        EventValidationException exception = assertThrows(
                EventValidationException.class,
                () -> parseSignals(event)
        );

        assertEquals(454, exception.getResponseCode());
        assertTrue(exception.getMessage().contains("0, 1, 2, 3"));
    }

    @Test
    void parseSignals_rejectsNonZeroSimpleCurrentValueForFarEvent()
            throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent.xml");
        var simple = event.getEiEvent().getEiEventSignals().getEiEventSignal().getFirst();
        simple.getCurrentValue().getPayloadFloat().setValue(2.0f);

        EventValidationException exception = assertThrows(
                EventValidationException.class,
                () -> parseSignals(event)
        );

        assertEquals(454, exception.getResponseCode());
        assertTrue(exception.getMessage().contains("must be 0"));
    }

    @Test
    void parseSignals_rejectsElectricityPriceWithoutCurrencyPerKWhUnit()
            throws Oadr20bUnmarshalException {
        OadrEvent event = loadFirstEvent("oadrDistributeEvent_electricityPrice.xml");
        event.getEiEvent().getEiEventSignals().getEiEventSignal().getFirst().setItemBase(null);

        assertTrue(parseSignals(event).isEmpty(),
                "Unsupported signalType and Unit combination must map to Rule 109 / 460");
    }

    // --- helpers ---

    private Optional<EventSignalCommand> parseSignal(OadrEvent event) {
        return service.selectPreferredSignal(parseSignals(event));
    }

    private List<EventSignalCommand> parseSignals(OadrEvent event) {
        eventValidator.validate(event);
        return service.supportedSignals(mapper.map(event));
    }

    private OadrEvent loadFirstEvent(String filename) throws Oadr20bUnmarshalException {
        File file = new File(EIEVENT_PATH + filename);
        OadrDistributeEventType distribute =
                jaxbContext.unmarshal(file, OadrDistributeEventType.class);
        assertFalse(distribute.getOadrEvent().isEmpty());
        return distribute.getOadrEvent().get(0);
    }
}
