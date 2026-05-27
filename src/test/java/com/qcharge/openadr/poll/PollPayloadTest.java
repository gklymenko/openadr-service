package com.qcharge.openadr.poll;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bPollBuilders;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PollPayloadTest extends AbstractOadrTest {

    @Test
    void poll_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrPollType payload = Oadr20bPollBuilders
                .newOadr20bPollBuilder("test-ven-001")
                .build();

        // Marshal → XML
        String xml = jaxbContext.marshalRoot(payload, true);

        assertNotNull(xml);
        assertTrue(xml.contains("oadrPoll"));
        assertTrue(xml.contains("test-ven-001"));

        // Unmarshal → Object
        Object unmarshalled = jaxbContext.unmarshal(xml);
        assertNotNull(unmarshalled);
        assertInstanceOf(OadrPollType.class, unmarshalled);

        OadrPollType result = (OadrPollType) unmarshalled;
        assertEquals("test-ven-001", result.getVenID());
    }

    @Test
    void poll_venId_mustBePresent() throws Oadr20bMarshalException {

        OadrPollType payload = Oadr20bPollBuilders
                .newOadr20bPollBuilder("ven-001")
                .build();

        String xml = jaxbContext.marshalRoot(payload, true);

        // Conformance: venID must be present in oadrPoll
        assertTrue(xml.contains("venID") || xml.contains("venId"),
                "oadrPoll must contain venID per conformance rule");
    }
}