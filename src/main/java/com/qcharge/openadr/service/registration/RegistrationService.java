package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.event.DrEventHandler;
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
    private final DrEventHandler drEventHandler;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Checking VEN registration status...");
        String venId = properties.getVen().getId();

        Optional<VenRegistration> existing = registrationRepository.findByVenIdAndStatus(
                venId, VenRegistration.RegistrationStatus.REGISTERED
        );

        if (existing.isPresent()) {
            log.info("VEN already registered: {}", existing.get().getRegistrationId());
            // per conformance rule 405: not required to send oadrRequestEvent for re-registration
        } else {
            log.info("VEN not registered, starting registration flow...");
            register();
        }
    }

    public void register() {
        String venId = properties.getVen().getId();
        String venProfile = properties.getVen().getProfile();
        String requestId = UUID.randomUUID().toString();

        OadrCreatePartyRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCreatePartyRegistrationBuilder(requestId, venId, venProfile)
                        .withOadrTransportName(OadrTransportType.SIMPLE_HTTP)
                        .withOadrTransportAddress(null)
                        .withOadrReportOnly(false)
                        .withOadrXmlSignature(false)
                        .withOadrHttpPullModel(true)
                        .build();

        log.info("Sending oadrCreatePartyRegistration for venId: {}", venId);

        Object response = transportService.send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, payload);

        if (response instanceof OadrCreatedPartyRegistrationType created) {
            handleRegistrationResponse(created);
        } else {
            log.error("Unexpected response type: {}", response != null ? response.getClass().getName() : "null");
        }
    }

    private void handleRegistrationResponse(OadrCreatedPartyRegistrationType response) {
        String responseCode = response.getEiResponse().getResponseCode();

        if (!"200".equals(responseCode)) {
            log.error(
                    "Registration failed, code: {}, description: {}", responseCode,
                    response.getEiResponse().getResponseDescription()
            );
            return;
        }

        String registrationId = response.getRegistrationID();
        String venId = response.getVenID();
        String vtnId = response.getVtnID();

        VenRegistration registration = new VenRegistration();
        registration.setVenId(venId);
        registration.setVtnId(vtnId);
        registration.setRegistrationId(registrationId);
        registration.setStatus(VenRegistration.RegistrationStatus.REGISTERED);
        registration.setRegisteredAt(LocalDateTime.now());
        registration.setUpdatedAt(LocalDateTime.now());
        registrationRepository.save(registration);

        log.info("VEN registered. registrationId: {}, vtnId: {}", registrationId, vtnId);

        // Conformance rule 405: after new registration MUST:
        // 1. Exchange Metadata reports
        // 2. Send oadrRequestEvent
        reportService.registerReports();
        requestAllEvents();
    }

    /**
     * Conformance rule 405:
     * After new registration, VEN must use oadrRequestEvent
     * to get all relevant events before initiating other exchanges.
     */
    private void requestAllEvents() {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        OadrRequestEventType requestEvent = Oadr20bEiEventBuilders
                .newOadrRequestEventBuilder(venId, requestId)
                .withReplyLimit(10L)
                .build();

        log.info("Sending oadrRequestEvent to get all active events");

        Object response = transportService.send(Oadr20bUrlPath.EI_EVENT_SERVICE, requestEvent);

        switch (response) {
            case OadrDistributeEventType distributeEvent -> {
                int count = distributeEvent.getOadrEvent().size();
                if (count > 0) {
                    log.info("Received {} active events after registration", count);
                    drEventHandler.handle(distributeEvent);
                } else {
                    log.info("No active events at registration time");
                }
            }
            case OadrResponseType oadrResponse -> log.info(
                    "oadrRequestEvent response: {}", oadrResponse.getEiResponse().getResponseCode()
            );
            default -> log.warn("Unexpected oadrRequestEvent response: {}", response.getClass().getName());
        }
    }
}