package com.qcharge.openadr.transport;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAdrXmlValidationTest extends AbstractOadrTest {

    @Test
    void rejectsWholePayloadWhenXmlDoesNotMatchSchema() {
        File payload = new File(POLL_PATH + "unvalidationOadrPoll.xml");

        assertThrows(
                Oadr20bUnmarshalException.class,
                () -> jaxbContext.unmarshal(payload, OadrPollType.class)
        );
    }

    @Test
    void acceptsSchemaValidPayloadWithDuplicateEventIdentityForBusinessLayer() throws Exception {
        File payload = new File(EIEVENT_PATH + "oadrDistributeEvent.xml");
        OadrDistributeEventType distributeEvent = jaxbContext.unmarshal(
                payload,
                OadrDistributeEventType.class
        );
        distributeEvent.getOadrEvent().add(distributeEvent.getOadrEvent().getFirst());

        assertDoesNotThrow(() -> jaxbContext.marshalRoot(distributeEvent));
    }
}
