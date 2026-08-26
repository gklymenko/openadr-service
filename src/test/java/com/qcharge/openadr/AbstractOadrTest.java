package com.qcharge.openadr;

import com.qcharge.openadr.config.OpenAdrXmlConfiguration;
import com.qcharge.openadr.service.transport.xml.OpenAdrXmlCodec;
import org.junit.jupiter.api.BeforeAll;

public abstract class AbstractOadrTest {

    protected static OpenAdrXmlCodec jaxbContext;

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
    static void initJaxb() throws Exception {
        OpenAdrXmlConfiguration configuration = new OpenAdrXmlConfiguration();
        jaxbContext = new OpenAdrXmlCodec(
                configuration.openAdrSchema(),
                configuration.openAdrJaxbContext()
        );
    }
}
