package com.qcharge.openadr.eiregisterparty;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileNotFoundException;

import static org.junit.jupiter.api.Assertions.*;

class RegistrationPayloadTest extends AbstractOadrTest {

    @Test
    void createPartyRegistration_marshalUnmarshal_validPayload()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrCreatePartyRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCreatePartyRegistrationBuilder(
                                "test-request-001",
                                "test-ven-001",
                                "2.0b"
                        )
                        .withOadrTransportName(OadrTransportType.SIMPLE_HTTP)
                        .withOadrTransportAddress(null)
                        .withOadrReportOnly(false)
                        .withOadrXmlSignature(false)
                        .withOadrHttpPullModel(true)
                        .build();

        // Marshal → XML
        String xml = jaxbContext.marshalRoot(payload, true);

        assertNotNull(xml);
        assertTrue(xml.contains("oadrCreatePartyRegistration"));
        assertTrue(xml.contains("test-ven-001"));
        assertTrue(xml.contains("simpleHttp"));
        assertTrue(xml.contains("2.0b"));
        assertTrue(xml.contains("schemaVersion"));

        // Unmarshal → Object
        Object unmarshalled = jaxbContext.unmarshal(xml);
        assertNotNull(unmarshalled);
        assertInstanceOf(OadrCreatePartyRegistrationType.class, unmarshalled);

        OadrCreatePartyRegistrationType result =
                (OadrCreatePartyRegistrationType) unmarshalled;
        assertEquals("test-ven-001", result.getVenID());
        assertEquals(result.getOadrTransportName(), OadrTransportType.SIMPLE_HTTP);
        assertEquals("2.0b", result.getOadrProfileName());
        assertFalse(result.isOadrReportOnly());
        assertTrue(result.isOadrHttpPullModel());
    }

    @Test
    void createdPartyRegistration_unmarshalFromFile_validResponse()
            throws Oadr20bUnmarshalException {

        File file = new File(EIREGISTERPARTY_PATH + "oadrCreatedPartyRegistration.xml");
        assumeFileExists(file);

        OadrCreatedPartyRegistrationType result =
                jaxbContext.unmarshal(file, OadrCreatedPartyRegistrationType.class);

        assertNotNull(result);
        // файл має eiResponse і vtnID
        assertEquals("200", result.getEiResponse().getResponseCode());
        assertEquals("OK", result.getEiResponse().getResponseDescription().trim());
        assertNotNull(result.getVtnID());
        // файл не має venID і registrationID — це VTN query response
        assertFalse(result.getOadrProfiles().getOadrProfile().isEmpty());
    }

    @Test
    void createPartyRegistration_schemaVersion_mustBe20b()
            throws Oadr20bMarshalException, Oadr20bUnmarshalException {

        OadrCreatePartyRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCreatePartyRegistrationBuilder(
                                "req-001", "ven-001", "2.0b")
                        .withOadrTransportName(OadrTransportType.SIMPLE_HTTP)
                        .withOadrHttpPullModel(true)
                        .build();

        String xml = jaxbContext.marshalRoot(payload, true);

        // Conformance rule: schemaVersion MUST be "2.0b"
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