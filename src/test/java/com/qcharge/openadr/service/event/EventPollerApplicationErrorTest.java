package com.qcharge.openadr.service.event;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
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

@ExtendWith(MockitoExtension.class)
class EventPollerApplicationErrorTest {

    @Mock OpenAdrProperties properties;
    @Mock VtnTransportService transportService;
    @Mock DrEventHandler drEventHandler;
    @Mock ReportRequestHandler reportRequestHandler;
    @Mock TaskScheduler taskScheduler;
    @Mock OpenAdrApplicationErrorMapper applicationErrorMapper;
    @Mock OpenAdrReplyFactory replyFactory;
    @Mock ObjectProvider<RegistrationService> registrationServiceProvider;
    @Mock RegistrationService registrationService;
    @Mock OpenAdrSessionProvider sessionProvider;

    private EventPoller eventPoller;

    @BeforeEach
    void setUp() {
        eventPoller = new EventPoller(
                properties,
                transportService,
                drEventHandler,
                reportRequestHandler,
                taskScheduler,
                applicationErrorMapper,
                replyFactory,
                sessionProvider,
                registrationServiceProvider
        );
    }

    @Test
    void notRegistered_stopsPollingAndStartsReregistration() {
        when(registrationServiceProvider.getObject())
                .thenReturn(registrationService);

        eventPoller.handlePollingApplicationError(
                applicationError(
                        ApplicationLayerErrorCodes.NOT_REGISTERED,
                        ApplicationErrorAction.REQUIRE_REREGISTRATION
                )
        );

        verify(registrationService).register();
    }

    @Test
    void ordinaryApplicationError_doesNotStartReregistration() {
        eventPoller.handlePollingApplicationError(
                applicationError(
                        ApplicationLayerErrorCodes.INVALID_ID,
                        ApplicationErrorAction.FAIL_OPERATION
                )
        );

        verify(registrationServiceProvider, never()).getObject();
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
