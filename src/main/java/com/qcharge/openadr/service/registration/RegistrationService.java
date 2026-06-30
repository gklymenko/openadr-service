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
import com.qcharge.openadr.repository.OptScheduleRepository;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.repository.VenReportRepository;
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
import java.time.Instant;
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
    private final VenReportRepository venReportRepository;
    private final OptScheduleRepository optScheduleRepository;
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

    //TODO:: extract external call from transaction, to avoid stuck transaction while network call is executing to VTN.
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

        // registrationId MUST only come from local DB state, never from oadrQueryRegistration
        // responses — rule 406: a query is informational only and must not influence registration.
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

    @Transactional
    public void initiateCancelRegistration() {
        String venId = properties.getVen().getId();

        VenRegistration registration = registrationRepository
                .findByVenIdAndStatus(venId, VenRegistration.RegistrationStatus.REGISTERED)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot cancel: VEN is not currently registered"));

        String registrationId = registration.getRegistrationId();
        String requestId = UUID.randomUUID().toString();

        log.info("VEN-initiated cancellation. registrationId={}", registrationId);

        OadrCancelPartyRegistrationType payload = Oadr20bEiRegisterPartyBuilders
                .newOadr20bCancelPartyRegistrationBuilder(requestId, registrationId, venId)
                .build();

        Object response = transportService.send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, payload);

        if (response instanceof OadrCanceledPartyRegistrationType canceled) {
            log.info("Registration cancelled. responseCode={}",
                    canceled.getEiResponse().getResponseCode());
        } else {
            log.warn("Unexpected response to oadrCancelPartyRegistration: {}",
                    response == null ? "null" : response.getClass().getName());
        }

        registration.setStatus(VenRegistration.RegistrationStatus.CANCELLED);
        registration.setUpdatedAt(nowUtc());
        registrationRepository.save(registration);

        eventPoller.stop();
    }

    @Transactional
    public void initiateForcedNewRegistration() {
        log.warn("Forcing NEW registration (no registrationID), per test N1_0060 requirement");

        String venId = properties.getVen().getId();
        String requestId = UUID.randomUUID().toString();

        // IMPORTANT: deliberately do NOT look up existing registrationId from DB.
        // This must be a clean "new registration" request per rule 406, even
        // though we may already have an active registration recorded locally.
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

        OadrCreatePartyRegistrationType payload = builder.build();

        log.info("Sending FORCED NEW oadrCreatePartyRegistration (no registrationID). venId={}", venId);

        Object response = transportService.send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, payload);

        if (!(response instanceof OadrCreatedPartyRegistrationType created)) {
            throw new IllegalStateException(
                    "Unexpected response to forced new oadrCreatePartyRegistration: "
                            + (response == null ? "null" : response.getClass().getName()));
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

        // Rule 406: capture previous registrationId before overwrite
        String configuredVenId = properties.getVen().getId();
        String previousRegistrationId = registrationRepository
                .findByVenIdAndStatus(configuredVenId, VenRegistration.RegistrationStatus.REGISTERED)
                .map(VenRegistration::getRegistrationId)
                .orElse(null);

        VenRegistration registration = saveRegistration(response);
        Duration pollInterval = extractRequestedPollFrequency(response);

        // Rule 406: VTN assigned new registrationId → erase stale report/opt data
        if (previousRegistrationId != null
                && !previousRegistrationId.equals(registration.getRegistrationId())) {
            log.warn("Rule 406: VTN assigned new registrationId={}. Erasing stale report/opt data.",
                    registration.getRegistrationId());
            eraseReportAndOptData();
        }

        log.info(
                "VEN registered. venId={}, vtnId={}, registrationId={}, pollInterval={}",
                registration.getVenId(),
                registration.getVtnId(),
                registration.getRegistrationId(),
                pollInterval
        );

        runPostRegistrationFlow(pollInterval);
    }

    private void eraseReportAndOptData() {
        venReportRepository.deleteAll();
        optScheduleRepository.deleteAll();
        log.info("Rule 406: cleared all VenReport and OptSchedule records");
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

    private Instant nowUtc() {
        return Instant.now();
    }
}