package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.registration.RegistrationMessageHandler;
import com.qcharge.openadr.service.report.PulledReportCommand;
import com.qcharge.openadr.service.report.ReportCommandQueue;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventPollerQueueDrainTest {

    @Mock OpenAdrProperties properties;
    @Mock OpenAdrProperties.Transport transportProperties;
    @Mock VtnTransportService transportService;
    @Mock EventProtocolAdapter eventProtocolAdapter;
    @Mock ReportCommandQueue reportCommandQueue;
    @Mock TaskScheduler taskScheduler;
    @Mock OpenAdrApplicationErrorMapper applicationErrorMapper;
    @Mock OpenAdrReplyFactory replyFactory;
    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;
    @Mock RegistrationMessageHandler registrationMessageHandler;
    @Mock ScheduledFuture<?> scheduledFuture;

    private EventPoller eventPoller;

    @BeforeEach
    void setUp() {
        eventPoller = new EventPoller(
                properties,
                transportService,
                eventProtocolAdapter,
                reportCommandQueue,
                taskScheduler,
                applicationErrorMapper,
                replyFactory,
                lifecycleCoordinator,
                registrationMessageHandler
        );
    }

    @Test
    void drainsVtnQueueBeforeSubmittingReportCommandsForProcessing() {
        OpenAdrSessionSnapshot session = session();
        OadrCreateReportType first = new OadrCreateReportType();
        OadrCreateReportType second = new OadrCreateReportType();
        OadrResponseType queueEmpty = Oadr20bResponseBuilders
                .newOadr20bResponseBuilder("poll", 200, session.venId())
                .build();
        ArgumentCaptor<Runnable> pollTask = ArgumentCaptor.forClass(Runnable.class);

        when(properties.getTransport()).thenReturn(transportProperties);
        when(transportProperties.getMaxPollDrainIterations()).thenReturn(10);
        when(lifecycleCoordinator.requireRegisteredSession()).thenReturn(session);
        doAnswer(invocation -> Optional.of(
                invocation.getArgument(1, Supplier.class).get()
        )).when(lifecycleCoordinator).executeIfActive(eq(session), any());
        when(transportService.send(eq(OpenAdrOperations.POLL), any(), eq(session)))
                .thenReturn(first, second, queueEmpty);
        doReturn(scheduledFuture).when(taskScheduler)
                .schedule(pollTask.capture(), any(Trigger.class));

        eventPoller.start(Duration.ofSeconds(10));
        pollTask.getValue().run();

        InOrder order = inOrder(transportService, reportCommandQueue);
        order.verify(transportService).send(eq(OpenAdrOperations.POLL), any(), eq(session));
        order.verify(transportService).send(eq(OpenAdrOperations.POLL), any(), eq(session));
        order.verify(transportService).send(eq(OpenAdrOperations.POLL), any(), eq(session));
        order.verify(reportCommandQueue).enqueueAll(any());

        ArgumentCaptor<Collection<PulledReportCommand>> commands = ArgumentCaptor.forClass(Collection.class);
        verify(reportCommandQueue).enqueueAll(commands.capture());
        assertThat(commands.getValue())
                .extracting(PulledReportCommand::payload)
                .containsExactly(first, second);
    }

    private OpenAdrSessionSnapshot session() {
        return new OpenAdrSessionSnapshot(
                1L,
                1L,
                "VEN-1",
                "VTN-1",
                "REGISTRATION-1",
                Duration.ofSeconds(10)
        );
    }
}
