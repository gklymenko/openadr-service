package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.report.ReportService;
import com.qcharge.openadr.utility.RequestUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PostRegistrationBootstrapListener {

    private final ReportService reportService;
    private final ReportRequestHandler reportRequestHandler;
    private final RegistrationService registrationService;

    @EventListener
    public void bootstrap(PostRegistrationBootstrapEvent event) {
        OadrRegisteredReportType registeredReport = reportService.registerReportingCapabilities(event.session());

        reportRequestHandler.handleRegisteredReport(registeredReport, event.session());

        registrationService.requestAllEvents(event.session(), RequestUtils.newRequestId());
    }
}
