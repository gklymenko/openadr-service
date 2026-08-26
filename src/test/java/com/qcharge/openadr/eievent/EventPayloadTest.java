package com.qcharge.openadr.eievent;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class EventPayloadTest extends AbstractOadrTest {

    @Test
    void createdEvent_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        var eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseOK("req-001");

        var eventResponse = Oadr20bEiEventBuilders
                .newOadr20bCreatedEventEventResponseBuilder(
                        "event-001", 0L, "req-001", 200, OptTypeType.OPT_IN)
                .build();

        OadrCreatedEventType payload = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, "ven-001")
                .addEventResponse(eventResponse)
                .build();

        // Marshal → XML
        String xml = jaxbContext.marshalRoot(payload);

        assertNotNull(xml);
        assertTrue(xml.contains("oadrCreatedEvent"));
        assertTrue(xml.contains("ven-001"));
        assertTrue(xml.contains("optIn"));
        assertTrue(xml.contains("200"));

        // Unmarshal → Object
        Object unmarshalled = jaxbContext.unmarshal(xml);
        assertNotNull(unmarshalled);
        assertInstanceOf(OadrCreatedEventType.class, unmarshalled);

        OadrCreatedEventType result = (OadrCreatedEventType) unmarshalled;
        assertEquals("ven-001", result.getEiCreatedEvent().getVenID());
        assertEquals("200",
                result.getEiCreatedEvent().getEiResponse().getResponseCode());
        assertEquals(1,
                result.getEiCreatedEvent().getEventResponses()
                        .getEventResponse().size());
        assertEquals("event-001",
                result.getEiCreatedEvent().getEventResponses()
                        .getEventResponse().get(0)
                        .getQualifiedEventID().getEventID());
    }

    @Test
    void distributeEvent_unmarshalFromFile_validEvent()
            throws Oadr20bUnmarshalException {

        File file = new File(EIEVENT_PATH + "oadrDistributeEvent.xml");
        assumeFileExists(file);

        OadrDistributeEventType result =
                jaxbContext.unmarshal(file, OadrDistributeEventType.class);

        assertNotNull(result);
        assertNotNull(result.getVtnID());
        assertFalse(result.getOadrEvent().isEmpty(),
                "DistributeEvent must contain at least one event");

        var event = result.getOadrEvent().get(0).getEiEvent();
        assertNotNull(event.getEventDescriptor().getEventID());
        assertNotNull(event.getEventDescriptor().getEventStatus());
    }

    @Test
    void createdEvent_optIn_conformanceRule()
            throws Oadr20bMarshalException {

        var eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseOK("req-001");

        var eventResponse = Oadr20bEiEventBuilders
                .newOadr20bCreatedEventEventResponseBuilder(
                        "event-001", 0L, "req-001", 200, OptTypeType.OPT_IN)
                .build();

        OadrCreatedEventType payload = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, "ven-001")
                .addEventResponse(eventResponse)
                .build();

        String xml = jaxbContext.marshalRoot(payload);

        // Conformance: optType must be present
        assertTrue(xml.contains("optIn") || xml.contains("optOut"),
                "oadrCreatedEvent must contain optType");
        // Conformance: schemaVersion must be 2.0b
        assertTrue(xml.contains("2.0b"),
                "schemaVersion must be 2.0b");
    }

    private void assumeFileExists(File file) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                file.exists(),
                "Test XML file not found: " + file.getPath()
        );
    }
}
