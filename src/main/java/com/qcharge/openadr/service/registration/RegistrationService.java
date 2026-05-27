package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.report.ReportService;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final OpenAdrProperties properties;
    private final VenRegistrationRepository registrationRepository;
    private final VtnTransportService transportService;
    private final ReportService reportService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Checking VEN registration status...");
        String venId = properties.getVen().getId();

        Optional<VenRegistration> existing = registrationRepository
                .findByVenIdAndStatus(venId, VenRegistration.RegistrationStatus.REGISTERED);

        if (existing.isPresent()) {
            log.info("VEN already registered with registrationId: {}",
                    existing.get().getRegistrationId());
            return;
        }

        log.info("VEN not registered, starting registration flow...");
        register();
    }

    public void register() {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        OadrCreatePartyRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCreatePartyRegistrationBuilder(
                                requestId,
                                venId,
                                "2.0b"
                        )
                        .withOadrTransportName(OadrTransportType.SIMPLE_HTTP)
                        .withOadrTransportAddress(null)
                        .withOadrReportOnly(false)
                        .withOadrXmlSignature(false)
                        .withOadrHttpPullModel(true)
                        .build();

        log.info("Sending oadrCreatePartyRegistration for venId: {}", venId);

        // Надсилаємо до VTN
        Object response = transportService.send(
                Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE,
                payload
        );

        // Обробляємо відповідь
        if (response instanceof OadrCreatedPartyRegistrationType created) {
            handleRegistrationResponse(created);
        } else {
            log.error("Unexpected response type: {}", response.getClass().getName());
        }
    }

    private void handleRegistrationResponse(OadrCreatedPartyRegistrationType response) {
        String responseCode = response.getEiResponse().getResponseCode();
        String registrationId = response.getRegistrationID();
        String venId = response.getVenID();
        String vtnId = response.getVtnID();

        if (!"200".equals(responseCode)) {
            log.error("Registration failed, responseCode: {}, description: {}",
                    responseCode,
                    response.getEiResponse().getResponseDescription());
            return;
        }

        VenRegistration registration = new VenRegistration();
        registration.setVenId(venId);
        registration.setVtnId(vtnId);
        registration.setRegistrationId(registrationId);
        registration.setStatus(VenRegistration.RegistrationStatus.REGISTERED);
        registration.setRegisteredAt(LocalDateTime.now());
        registration.setUpdatedAt(LocalDateTime.now());

        registrationRepository.save(registration);

        reportService.registerReports();

        log.info("VEN registered successfully. registrationId: {}, vtnId: {}",
                registrationId, vtnId);
    }
}
