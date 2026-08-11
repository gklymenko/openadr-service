package com.qcharge.openadr.eievent;

import com.qcharge.openadr.AbstractOadrTest;
import com.qcharge.openadr.TestSessionFixtures;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.integration.ocpp.OcppIntegrationService;
import com.qcharge.openadr.model.entity.DrEvent;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.repository.DrEventRepository;
import com.qcharge.openadr.service.event.DrEventHandler;
import com.qcharge.openadr.service.event.EventOptDecisionService;
import com.qcharge.openadr.service.event.EventValidationService;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DrEventHandlerPersistenceTest extends AbstractOadrTest {

    @Test
    void handle_persistsCompleteSignalAndIntervalPlan() throws Oadr20bUnmarshalException {
        OpenAdrProperties properties = new OpenAdrProperties();
        properties.getReport().setResourceId("RES_123");

        DrEventRepository repository = mock(DrEventRepository.class);
        when(repository.findByEventId("Event_939393")).thenReturn(Optional.empty());

        DrEventHandler handler = new DrEventHandler(
                properties,
                repository,
                mock(VtnTransportService.class),
                new EventOptDecisionService(),
                new EventValidationService(properties),
                mock(OcppIntegrationService.class),
                mock(OpenAdrSessionProvider.class)
        );

        OadrDistributeEventType distributeEvent = jaxbContext.unmarshal(
                new File(EIEVENT_PATH + "oadrDistributeEvent.xml"),
                OadrDistributeEventType.class
        );

        handler.handle(
                distributeEvent,
                TestSessionFixtures.registeredSession("VEN-1", "VTN-1", "REG-1")
        );

        ArgumentCaptor<DrEvent> captor = ArgumentCaptor.forClass(DrEvent.class);
        verify(repository).save(captor.capture());
        DrEvent saved = captor.getValue();

        assertEquals(2, saved.getSignals().size());
        assertEquals("SIG_01", saved.getSignals().getFirst().getSignalId());
        assertEquals(2, saved.getSignals().getFirst().getIntervals().size());
        assertEquals("0", saved.getSignals().getFirst().getIntervals().getFirst().getIntervalUid());
        assertEquals(900L, saved.getSignals().getFirst().getIntervals().getFirst().getDurationSeconds());
        assertSame(saved, saved.getSignals().getFirst().getEvent());
        assertSame(
                saved.getSignals().getFirst(),
                saved.getSignals().getFirst().getIntervals().getFirst().getSignal()
        );
    }
}
