package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.ei.SchemaVersionEnumeratedType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.event.DrEventHandler;
import com.qcharge.openadr.service.event.EventPoller;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.report.ReportService;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final String RESPONSE_OK = "200";
    private static final int RESPONSE_CODE_OK = 200;
    private static final int REQUEST_EVENT_REPLY_LIMIT = 10;

    private final OpenAdrProperties properties;
    private final VenRegistrationRepository registrationRepository;
    private final VtnTransportService transportService;
    private final ReportService reportService;
    private final ReportRequestHandler reportRequestHandler;
    private final DrEventHandler drEventHandler;
    private final EventPoller eventPoller;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Starting OpenADR VEN bootstrap. venId={}", properties.getVen().getId());

        try {
            bootstrap();
        } catch (Exception e) {
            eventPoller.stop();
            log.error("OpenADR VEN bootstrap failed", e);
        }
    }

    @Transactional
    public void bootstrap() {
        if (properties.getVen().isQueryRegistrationOnStartup()) {
            queryRegistration();
        }

        register();
    }

    public void queryRegistration() {
        String requestId = UUID.randomUUID().toString();

        OadrQueryRegistrationType payload = Oadr20bEiRegisterPartyBuilders
                .newOadr20bQueryRegistrationBuilder(requestId)
                .build();

        log.info("Sending optional oadrQueryRegistration. requestId={}", requestId);

        Object response = transportService.send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, payload);

        if (response instanceof OadrCreatedPartyRegistrationType created) {
            log.info(
                    "oadrQueryRegistration response. code={}, vtnId={}",
                    created.getEiResponse().getResponseCode(),
                    created.getVtnID()
            );
            return;
        }

        log.warn(
                "Unexpected oadrQueryRegistration response: {}",
                response == null ? "null" : response.getClass().getName()
        );
    }

    @Transactional
    public void register() {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        var builder = Oadr20bEiRegisterPartyBuilders
                .newOadr20bCreatePartyRegistrationBuilder(requestId, venId, properties.getVen().getProfile())
                .withOadrTransportName(OadrTransportType.SIMPLE_HTTP)
                .withOadrTransportAddress(null)
                .withOadrReportOnly(false)
                .withOadrXmlSignature(false)
                .withOadrHttpPullModel(true);

        if (properties.getVen().getName() != null && !properties.getVen().getName().isBlank()) {
            builder.withOadrVenName(properties.getVen().getName());
        }

        registrationRepository
                .findByVenIdAndStatus(venId, VenRegistration.RegistrationStatus.REGISTERED)
                .map(VenRegistration::getRegistrationId)
                .filter(registrationId -> !registrationId.isBlank())
                .ifPresent(builder::withRegistrationId);

        OadrCreatePartyRegistrationType payload = builder.build();

        log.info("Sending oadrCreatePartyRegistration. venId={}, requestId={}", venId, requestId);

        Object response = transportService.send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, payload);

        if (!(response instanceof OadrCreatedPartyRegistrationType created)) {
            throw new IllegalStateException(
                    "Unexpected response to oadrCreatePartyRegistration: "
                            + (response == null ? "null" : response.getClass().getName())
            );
        }

        handleCreatedPartyRegistration(created);
    }

    public void handleRequestReregistration(OadrRequestReregistrationType request) {
        String venId = properties.getVen().getId();

        log.info("Received oadrRequestReregistration. venId={}", request.getVenID());

        OadrResponseType response = Oadr20bResponseBuilders
                .newOadr20bResponseBuilder("", RESPONSE_CODE_OK, venId)
                .build();

        transportService.send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, response);

        eventPoller.stop();
        register();
    }

    @Transactional
    public void handleCancelPartyRegistration(OadrCancelPartyRegistrationType request) {
        String venId = properties.getVen().getId();
        String registrationId = request.getRegistrationID();

        log.info("Received oadrCancelPartyRegistration. registrationId={}", registrationId);

        registrationRepository
                .findByVenIdAndStatus(venId, VenRegistration.RegistrationStatus.REGISTERED)
                .ifPresent(registration -> {
                    registration.setStatus(VenRegistration.RegistrationStatus.CANCELLED);
                    registration.setUpdatedAt(nowUtc());
                    registrationRepository.save(registration);
                });

        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder(request.getRequestID(), RESPONSE_CODE_OK)
                .build();

        OadrCanceledPartyRegistrationType response = Oadr20bEiRegisterPartyBuilders
                .newOadr20bCanceledPartyRegistrationBuilder(eiResponse, registrationId, venId)
                .build();

        transportService.send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, response);

        eventPoller.stop();

        log.info("Registration cancelled. registrationId={}", registrationId);
    }

    private void handleCreatedPartyRegistration(OadrCreatedPartyRegistrationType response) {
        String responseCode = response.getEiResponse().getResponseCode();

        if (!RESPONSE_OK.equals(responseCode)) {
            throw new IllegalStateException(
                    "VEN registration failed. code=%s, description=%s"
                            .formatted(responseCode, response.getEiResponse().getResponseDescription())
            );
        }

        VenRegistration registration = saveRegistration(response);
        Duration pollInterval = extractRequestedPollFrequency(response);

        log.info(
                "VEN registered. venId={}, vtnId={}, registrationId={}, pollInterval={}",
                registration.getVenId(),
                registration.getVtnId(),
                registration.getRegistrationId(),
                pollInterval
        );

        runPostRegistrationFlow(pollInterval);
    }

    private VenRegistration saveRegistration(OadrCreatedPartyRegistrationType response) {
        String configuredVenId = properties.getVen().getId();
        String receivedVenId = response.getVenID();

        VenRegistration registration = registrationRepository
                .findByVenIdAndStatus(configuredVenId, VenRegistration.RegistrationStatus.REGISTERED)
                .orElseGet(VenRegistration::new);

        registration.setVenId(receivedVenId != null && !receivedVenId.isBlank() ? receivedVenId : configuredVenId);
        registration.setVtnId(response.getVtnID());
        registration.setRegistrationId(response.getRegistrationID());
        registration.setStatus(VenRegistration.RegistrationStatus.REGISTERED);

        if (registration.getRegisteredAt() == null) {
            registration.setRegisteredAt(nowUtc());
        }

        registration.setUpdatedAt(nowUtc());

        return registrationRepository.save(registration);
    }

    private void runPostRegistrationFlow(Duration pollInterval) {
        OadrRegisteredReportType registeredReport = reportService.registerReportingCapabilities();
        reportRequestHandler.handleRegisteredReport(registeredReport);

        requestAllEvents();

        eventPoller.start(pollInterval);
    }

    private void requestAllEvents() {
        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        OadrRequestEventType requestEvent = Oadr20bEiEventBuilders
                .newOadrRequestEventBuilder(venId, requestId)
                .withReplyLimit((long) REQUEST_EVENT_REPLY_LIMIT)
                .build();

        log.info("Sending oadrRequestEvent after registration. requestId={}", requestId);

        Object response = transportService.send(Oadr20bUrlPath.EI_EVENT_SERVICE, requestEvent);

        if (response instanceof OadrDistributeEventType distributeEvent) {
            log.info("Received {} event(s) after registration", distributeEvent.getOadrEvent().size());
            drEventHandler.handle(distributeEvent);
            return;
        }

        if (response instanceof OadrResponseType oadrResponse) {
            log.info("oadrRequestEvent returned response code={}", oadrResponse.getEiResponse().getResponseCode());
            return;
        }

        log.warn(
                "Unexpected oadrRequestEvent response: {}",
                response == null ? "null" : response.getClass().getName()
        );
    }

    private Duration extractRequestedPollFrequency(OadrCreatedPartyRegistrationType response) {
        if (response.getOadrRequestedOadrPollFreq() == null
                || response.getOadrRequestedOadrPollFreq().getDuration() == null
                || response.getOadrRequestedOadrPollFreq().getDuration().isBlank()) {
            return defaultPollInterval();
        }

        try {
            return Duration.parse(response.getOadrRequestedOadrPollFreq().getDuration());
        } catch (RuntimeException e) {
            log.warn(
                    "Cannot parse oadrRequestedOadrPollFreq={}. Using default.",
                    response.getOadrRequestedOadrPollFreq().getDuration()
            );
            return defaultPollInterval();
        }
    }

    private Duration defaultPollInterval() {
        return Duration.ofSeconds(properties.getTransport().getPollIntervalSeconds());
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}