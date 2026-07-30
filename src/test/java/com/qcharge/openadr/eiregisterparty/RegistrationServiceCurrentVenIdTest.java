package com.qcharge.openadr.eiregisterparty;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.repository.OptScheduleRepository;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.event.DrEventHandler;
import com.qcharge.openadr.service.event.EventPoller;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.report.ReportService;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceCurrentVenIdTest {

    @Mock VenRegistrationRepository registrationRepository;
    @Mock VenReportRepository venReportRepository;
    @Mock OptScheduleRepository optScheduleRepository;
    @Mock VtnTransportService transportService;
    @Mock ReportService reportService;
    @Mock ReportRequestHandler reportRequestHandler;
    @Mock DrEventHandler drEventHandler;
    @Mock EventPoller eventPoller;
    @Mock OpenAdrSessionProvider sessionProvider;
    @Mock OpenAdrProperties properties;

    RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        registrationService = new RegistrationService(
                properties,
                registrationRepository,
                venReportRepository,
                optScheduleRepository,
                transportService,
                reportService,
                reportRequestHandler,
                drEventHandler,
                eventPoller,
                sessionProvider
        );
    }

    @Test
    void currentVenId_returnsVtnAssignedId_whenRegistrationExists() {
        when(sessionProvider.current()).thenReturn(registeredSession("VEN063026_152944_129"));

        assertEquals("VEN063026_152944_129", registrationService.currentVenId());
    }

    @Test
    void currentVenId_returnsConfiguredId_whenNoRegistrationExists() {
        when(sessionProvider.current()).thenReturn(bootstrapSession());

        assertEquals("TH_VEN", registrationService.currentVenId());
    }

    @Test
    void currentVenId_returnsConfiguredId_whenRegistrationHasSameId() {
        when(sessionProvider.current()).thenReturn(registeredSession("TH_VEN"));

        assertEquals("TH_VEN", registrationService.currentVenId());
    }

    private OpenAdrSessionSnapshot bootstrapSession() {
        return new OpenAdrSessionSnapshot(
                null,
                "TH_VEN",
                "TH_VTN",
                null,
                Duration.ofSeconds(10)
        );
    }

    private OpenAdrSessionSnapshot registeredSession(String venId) {
        return new OpenAdrSessionSnapshot(
                1L,
                venId,
                "TH_VTN",
                "registration-id",
                Duration.ofSeconds(10)
        );
    }
}