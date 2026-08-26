package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrXmlConfiguration;
import com.qcharge.openadr.service.transport.xml.OpenAdrXmlCodec;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.service.event.protocol.VtnEventLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class VtnEventLoggerTest {

    @Test
    void logReceivedEvents_logsEventSignalsAndIntervals(CapturedOutput output) throws Exception {
        OpenAdrXmlConfiguration configuration = new OpenAdrXmlConfiguration();
        OpenAdrXmlCodec xmlCodec = new OpenAdrXmlCodec(
                configuration.openAdrSchema(),
                configuration.openAdrJaxbContext()
        );
        OadrDistributeEventType distributeEvent = xmlCodec.unmarshal(
                        new File("src/test/resources/openadr/eievent/oadrDistributeEvent.xml"),
                        OadrDistributeEventType.class
                );

        VtnEventLogger.logReceivedEvents(distributeEvent);

        String logs = output.getOut();
        assertTrue(logs.contains(
                "Received OpenADR events from VTN. vtnId=VTN_543, requestId=REQ_12345, eventCount=1"
        ));
        assertTrue(logs.contains("eventId=Event_939393"));
        assertTrue(logs.contains("status=FAR"));
        assertTrue(logs.contains("start=2001-12-17T09:40:47.000Z"));
        assertTrue(logs.contains("signalId=SIG_01, signalName=SIMPLE, signalType=LEVEL"));
        assertTrue(logs.contains("signalId=SIG_02, signalName=ELECTRICITY_PRICE, signalType=PRICE"));
        assertTrue(logs.contains("intervalIndex=0, uid=0, start=null, duration=PT15M, values=[3.0]"));
        assertTrue(logs.contains("intervalIndex=1, uid=1, start=null, duration=PT15M, values=[1.3]"));
    }
}
