package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.ApplicationErrorAction;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.qcharge.openadr.TestSessionFixtures.registeredSession;

@ExtendWith(MockitoExtension.class)
class EventPollerApplicationErrorTest {

    @Mock OpenAdrProperties properties;
    @Mock VtnTransportService transportService;
    @Mock EventProtocolAdapter eventProtocolAdapter;
    @Mock ReportRequestHandler reportRequestHandler;
    @Mock TaskScheduler taskScheduler;
    @Mock OpenAdrApplicationErrorMapper applicationErrorMapper;
    @Mock OpenAdrReplyFactory replyFactory;
    @Mock ObjectProvider<RegistrationService> registrationServiceProvider;
    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    private EventPoller eventPoller;

    @BeforeEach
    void setUp() {
        eventPoller = new EventPoller(
                properties,
                transportService,
                eventProtocolAdapter,
                reportRequestHandler,
                taskScheduler,
                applicationErrorMapper,
                replyFactory,
                lifecycleCoordinator,
                registrationServiceProvider
        );
    }

    @Test
    void notRegistered_stopsPollingAndStartsReregistration() {
        OpenAdrSessionSnapshot session = registeredSession();
        when(lifecycleCoordinator.requireRegisteredSession())
                .thenReturn(session);

        eventPoller.handlePollingApplicationError(
                applicationError(
                        OpenADRResponseCode.NOT_REGISTERED,
                        ApplicationErrorAction.REQUIRE_REREGISTRATION
                )
        );

        verify(lifecycleCoordinator).reregister(session);
    }

    @Test
    void ordinaryApplicationError_doesNotStartReregistration() {
        eventPoller.handlePollingApplicationError(
                applicationError(
                        OpenADRResponseCode.INVALID_ID,
                        ApplicationErrorAction.FAIL_OPERATION
                )
        );

        verify(lifecycleCoordinator, never())
                .reregister(org.mockito.ArgumentMatchers.any());
    }

    private OpenAdrApplicationException applicationError(
            int responseCode,
            ApplicationErrorAction action
    ) {
        return new OpenAdrApplicationException(
                "VTN rejected poll",
                responseCode,
                "description",
                "request-123",
                "poll",
                action
        );
    }
}
