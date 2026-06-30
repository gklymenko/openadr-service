package com.qcharge.openadr.eiregisterparty;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.repository.OptScheduleRepository;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.event.DrEventHandler;
import com.qcharge.openadr.service.event.EventPoller;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.report.ReportService;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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
    @Mock OpenAdrProperties properties;
    @Mock OpenAdrProperties.Ven venProperties;

    RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        when(properties.getVen()).thenReturn(venProperties);
        when(venProperties.getId()).thenReturn("TH_VEN");

        registrationService = new RegistrationService(
                properties,
                registrationRepository,
                venReportRepository,
                optScheduleRepository,
                transportService,
                reportService,
                reportRequestHandler,
                drEventHandler,
                eventPoller
        );
    }

    @Test
    void currentVenId_returnsVtnAssignedId_whenRegistrationExists() {
        VenRegistration registration = new VenRegistration();
        registration.setVenId("VEN063026_152944_129");

        when(registrationRepository.findFirstByStatusOrderByUpdatedAtDesc(
                VenRegistration.RegistrationStatus.REGISTERED
        )).thenReturn(Optional.of(registration));

        assertEquals("VEN063026_152944_129", registrationService.currentVenId());
    }

    @Test
    void currentVenId_returnsConfiguredId_whenNoRegistrationExists() {
        when(registrationRepository.findFirstByStatusOrderByUpdatedAtDesc(
                VenRegistration.RegistrationStatus.REGISTERED
        )).thenReturn(Optional.empty());

        assertEquals("TH_VEN", registrationService.currentVenId());
    }

    @Test
    void currentVenId_returnsConfiguredId_whenRegistrationHasSameId() {
        VenRegistration registration = new VenRegistration();
        registration.setVenId("TH_VEN");

        when(registrationRepository.findFirstByStatusOrderByUpdatedAtDesc(
                VenRegistration.RegistrationStatus.REGISTERED
        )).thenReturn(Optional.of(registration));

        assertEquals("TH_VEN", registrationService.currentVenId());
    }
}