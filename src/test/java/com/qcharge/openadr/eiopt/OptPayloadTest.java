package com.qcharge.openadr.eiopt;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiOptBuilders;
import com.qcharge.openadr.model.oadr20b.ei.OptReasonEnumeratedType;
import com.qcharge.openadr.model.oadr20b.ei.OptTypeType;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class OptPayloadTest extends AbstractOadrTest {

    @Test
    void createOpt_optIn_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrCreateOptType payload = Oadr20bEiOptBuilders
                .newOadr20bCreateOptBuilder(
                        "req-001",
                        "ven-001",
                        System.currentTimeMillis(),
                        "event-001",
                        0L,
                        "opt-001",
                        OptTypeType.OPT_IN,
                        OptReasonEnumeratedType.ECONOMIC
                )
                .build();

        // Marshal → XML
        String xml = jaxbContext.marshalRoot(payload, true);

        assertNotNull(xml);
        assertTrue(xml.contains("oadrCreateOpt"));
        assertTrue(xml.contains("optIn"));
        assertTrue(xml.contains("ven-001"));
        assertTrue(xml.contains("opt-001"));
        assertTrue(xml.contains("2.0b"));

        // Unmarshal → Object
        Object unmarshalled = jaxbContext.unmarshal(xml);
        assertNotNull(unmarshalled);
        assertInstanceOf(OadrCreateOptType.class, unmarshalled);

        OadrCreateOptType result = (OadrCreateOptType) unmarshalled;
        assertEquals("ven-001", result.getVenID());
        assertEquals("opt-001", result.getOptID());
        assertEquals(OptTypeType.OPT_IN, result.getOptType());
    }

    @Test
    void createOpt_optOut_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrCreateOptType payload = Oadr20bEiOptBuilders
                .newOadr20bCreateOptBuilder(
                        "req-002",
                        "ven-001",
                        System.currentTimeMillis(),
                        "event-001",
                        0L,
                        "opt-002",
                        OptTypeType.OPT_OUT,
                        OptReasonEnumeratedType.NOT_PARTICIPATING
                )
                .build();

        String xml = jaxbContext.marshalRoot(payload, true);

        assertNotNull(xml);
        assertTrue(xml.contains("optOut"));
        assertTrue(xml.contains("notParticipating"));

        OadrCreateOptType result =
                (OadrCreateOptType) jaxbContext.unmarshal(xml);
        assertEquals(OptTypeType.OPT_OUT, result.getOptType());
    }

    @Test
    void cancelOpt_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrCancelOptType payload = Oadr20bEiOptBuilders
                .newOadr20bCancelOptBuilder("req-003", "opt-001", "ven-001")
                .build();

        String xml = jaxbContext.marshalRoot(payload, true);

        assertNotNull(xml);
        assertTrue(xml.contains("oadrCancelOpt"));
        assertTrue(xml.contains("opt-001"));
        assertTrue(xml.contains("ven-001"));

        Object unmarshalled = jaxbContext.unmarshal(xml);
        assertInstanceOf(OadrCancelOptType.class, unmarshalled);

        OadrCancelOptType result = (OadrCancelOptType) unmarshalled;
        assertEquals("opt-001", result.getOptID());
        assertEquals("ven-001", result.getVenID());
    }

    @Test
    void createOpt_unmarshalFromFile_validPayload()
            throws Oadr20bUnmarshalException {

        File file = new File(EIOPT_PATH + "oadrCreateOpt.xml");
        assumeFileExists(file);

        OadrCreateOptType result =
                jaxbContext.unmarshal(file, OadrCreateOptType.class);

        assertNotNull(result);
        assertEquals("Opt_1234", result.getOptID());
        assertEquals(OptTypeType.OPT_IN, result.getOptType());
        assertEquals("VEN_3214", result.getVenID());
        assertNotNull(result.getQualifiedEventID());
        assertEquals("Event_12345",
                result.getQualifiedEventID().getEventID());
        assertEquals(1L,
                result.getQualifiedEventID().getModificationNumber());
    }

    @Test
    void createdOpt_unmarshalFromFile_validResponse()
            throws Oadr20bUnmarshalException {

        File file = new File(EIOPT_PATH + "oadrCreatedOpt.xml");
        assumeFileExists(file);

        OadrCreatedOptType result =
                jaxbContext.unmarshal(file, OadrCreatedOptType.class);

        assertNotNull(result);
        assertEquals("200", result.getEiResponse().getResponseCode());
        assertNotNull(result.getOptID());
    }

    @Test
    void createOpt_schemaVersion_mustBe20b()
            throws Oadr20bMarshalException {

        OadrCreateOptType payload = Oadr20bEiOptBuilders
                .newOadr20bCreateOptBuilder(
                        "req-001", "ven-001",
                        System.currentTimeMillis(),
                        "event-001", 0L, "opt-001",
                        OptTypeType.OPT_IN,
                        OptReasonEnumeratedType.ECONOMIC
                )
                .build();

        String xml = jaxbContext.marshalRoot(payload, true);

        assertTrue(xml.contains("2.0b"),
                "schemaVersion must be 2.0b per conformance rule");
    }

    private void assumeFileExists(File file) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                file.exists(),
                "Test XML file not found: " + file.getPath()
        );
    }
}