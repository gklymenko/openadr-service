package com.qcharge.openadr;

import com.qcharge.openadr.model.oadr20b.Oadr20bJAXBContext;
import jakarta.xml.bind.JAXBException;
import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractOadrTest {

    protected static Oadr20bJAXBContext jaxbContext;

    // XSD path відносно кореня проекту
    protected static final String XSD_PATH = "src/main/resources/xsd";

    // XML test resources
    protected static final String EIEVENT_PATH =
            "src/test/resources/openadr/eievent/";
    protected static final String EIREPORT_PATH =
            "src/test/resources/openadr/eireport/";
    protected static final String EIREGISTERPARTY_PATH =
            "src/test/resources/openadr/eiregisterparty/";
    protected static final String EIOPT_PATH =
            "src/test/resources/openadr/eiopt/";
    protected static final String POLL_PATH =
            "src/test/resources/openadr/poll/";

    @BeforeAll
    static void initJaxb() throws JAXBException {
        jaxbContext = Oadr20bJAXBContext.getInstance(XSD_PATH);
    }
}