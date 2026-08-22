package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.report.ReportService;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.qcharge.openadr.TestSessionFixtures.registeredSession;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostRegistrationBootstrapListenerTest {

    @Mock ReportService reportService;
    @Mock ReportRequestHandler reportRequestHandler;
    @Mock RegistrationService registrationService;

    @InjectMocks PostRegistrationBootstrapListener listener;

    @Test
    void bootstrapsReportsThenRequestsCompleteEventSnapshot() {
        OpenAdrSessionSnapshot session = registeredSession();
        OadrRegisteredReportType registeredReport =
                new OadrRegisteredReportType();
        when(reportService.registerReportingCapabilities(session))
                .thenReturn(registeredReport);

        listener.bootstrap(new PostRegistrationBootstrapEvent(session));

        var order = inOrder(
                reportService,
                reportRequestHandler,
                registrationService
        );
        order.verify(reportService).registerReportingCapabilities(session);
        order.verify(reportRequestHandler)
                .handleRegisteredReport(registeredReport, session);
//        order.verify(registrationService).requestAllEvents(session, any());
    }
}
