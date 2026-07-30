package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
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
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final String RESPONSE_OK = "200";

    private static final int RESPONSE_CODE_OK = 200;
    private static final int RESPONSE_CODE_INVALID_ID = 452;
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
    private final OpenAdrSessionProvider sessionProvider;

    /**
     * Returns the VEN ID assigned by the VTN for the current active registration.
     * Configured VEN ID is used only before the first successful registration.
     */
    public String currentVenId() {
        return sessionProvider.current().venId();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Starting OpenADR VEN bootstrap. configuredVenId={}", properties.getVen().getId());

        try {
            bootstrap();
        } catch (Exception exception) {
            eventPoller.stop();
            log.error("OpenADR VEN bootstrap failed", exception);
        }
    }

    /**
     * Startup flow:
     * 1. Optionally, query supported registration capabilities.
     * 2. If no active registration exists, perform new registration.
     * 3. If active registration exists, perform re-registration using persisted IDs.
     * 4. Start polling using the frequency returned by the VTN or persisted earlier.
     */
    public void bootstrap() {
        if (properties.getVen().isQueryRegistrationOnStartup()) {
            queryRegistration();
        }

        Optional<VenRegistration> activeRegistration = findActiveRegistration();

        if (activeRegistration.isEmpty()) {
            log.info("No active VEN registration found. Performing new registration.");

            RegistrationResult result = registerNew();
            completeRegistration(result, true);
            return;
        }

        VenRegistration existing = activeRegistration.get();

        log.info(
                "Active registration found. Performing re-registration. venId={}, registrationId={}",
                existing.getVenId(), existing.getRegistrationId()
        );

        RegistrationResult result = reregister(existing);
        completeRegistration(result, false);
    }

    /**
     * Optional discovery call. Its response must never be used as the source
     * of venID or registrationID for an active registration.
     */
    public void queryRegistration() {
        OpenAdrSessionSnapshot session = sessionProvider.current();
        String requestId = newRequestId();

        OadrQueryRegistrationType payload = Oadr20bEiRegisterPartyBuilders
                .newOadr20bQueryRegistrationBuilder(requestId)
                .build();

        log.info(
                "Sending optional oadrQueryRegistration. requestId={}",
                requestId
        );

        Object response = transportService.send(
                OpenAdrOperations.QUERY_REGISTRATION,
                payload,
                session
        );

        if (response instanceof OadrCreatedPartyRegistrationType created) {
            log.info(
                    "oadrQueryRegistration completed. responseCode={}, vtnId={}",
                    responseCode(created),
                    created.getVtnID()
            );
            return;
        }

        log.warn(
                "Unexpected oadrQueryRegistration response. type={}",
                responseType(response)
        );
    }

    /**
     * Public entry point for a regular registration operation.
     * If an active persisted registration exists, this method performs
     * re-registration. Otherwise, it creates a new registration instance.
     */
    public void register() {
        Optional<VenRegistration> active = findActiveRegistration();

        if (active.isPresent()) {
            RegistrationResult result = reregister(active.get());
            completeRegistration(result, false);
            return;
        }

        RegistrationResult result = registerNew();
        completeRegistration(result, true);
    }

    /**
     * Creates a completely new registration request without registrationID.
     */
    private RegistrationResult registerNew() {
        OpenAdrSessionSnapshot session = sessionProvider.bootstrap();

        OadrCreatedPartyRegistrationType response =
                sendCreatePartyRegistration(session);

        validateCreatedPartyRegistration(response);

        VenRegistration registration = saveRegistration(response, null);

        return new RegistrationResult(registration, true);
    }

    /**
     * Re-registers using the VEN ID and registration ID stored in the database.
     */
    private RegistrationResult reregister(VenRegistration existing) {
        requireValidPersistedRegistration(existing);
        OpenAdrSessionSnapshot session = sessionProvider.fromRegistration(existing);

        String previousRegistrationId = existing.getRegistrationId();

        OadrCreatedPartyRegistrationType response =
                sendCreatePartyRegistration(session);

        validateCreatedPartyRegistration(response);

        String receivedRegistrationId = response.getRegistrationID();

        boolean newRegistrationInstance =
                !Objects.equals(
                        previousRegistrationId,
                        receivedRegistrationId
                );

        VenRegistration registration = saveRegistration(response, existing);

        return new RegistrationResult(registration, newRegistrationInstance);
    }

    private OadrCreatedPartyRegistrationType sendCreatePartyRegistration(
            OpenAdrSessionSnapshot session
    ) {
        String venId = session.venId();
        String registrationId = session.registrationId();
        String requestId = newRequestId();

        var builder = Oadr20bEiRegisterPartyBuilders
                .newOadr20bCreatePartyRegistrationBuilder(
                        requestId,
                        venId,
                        properties.getVen().getProfile()
                )
                .withOadrTransportName(OadrTransportType.SIMPLE_HTTP)
                .withOadrTransportAddress(null)
                .withOadrReportOnly(false)
                .withOadrXmlSignature(false)
                .withOadrHttpPullModel(true);

        if (hasText(registrationId)) {
            builder.withRegistrationId(registrationId);
        }

        if (hasText(properties.getVen().getName())) {
            builder.withOadrVenName(properties.getVen().getName());
        }

        OadrCreatePartyRegistrationType payload = builder.build();

        log.info(
                "Sending oadrCreatePartyRegistration. venId={}, requestId={}, reRegistration={}",
                venId, requestId, hasText(registrationId)
        );

        Object response = transportService.send(
                OpenAdrOperations.CREATE_PARTY_REGISTRATION,
                payload,
                session
        );

        if (!(response instanceof OadrCreatedPartyRegistrationType created)) {
            throw new IllegalStateException(
                    "Unexpected response to oadrCreatePartyRegistration. type=" + responseType(response)
            );
        }

        return created;
    }

    /**
     * Completes either new registration or re-registration.
     * For a new registration instance, old reports/options are invalidated and
     * the full metadata/event bootstrap is performed.
     * For an unchanged re-registration instance, polling is simply resumed.
     */
    private void completeRegistration(
            RegistrationResult result,
            boolean explicitlyNewRegistration
    ) {
        VenRegistration registration = result.registration();

        boolean runFullBootstrap =
                explicitlyNewRegistration
                        || result.newRegistrationInstance();

        if (runFullBootstrap) {
            eraseReportAndOptData();
            runPostRegistrationFlow(registration);
        }

        Duration pollInterval = resolvePollInterval(registration);

        eventPoller.start(pollInterval);

        log.info(
                "VEN registration flow completed. " +
                        "venId={}, vtnId={}, registrationId={}, " +
                        "newRegistrationInstance={}, pollInterval={}",
                registration.getVenId(),
                registration.getVtnId(),
                registration.getRegistrationId(),
                runFullBootstrap,
                pollInterval
        );
    }

    /**
     * Test-specific operation that deliberately sends a new registration
     * without registrationID even if an active registration exists.
     */
    public void initiateForcedNewRegistration() {
        log.warn("Forcing a new registration without registrationID");

        eventPoller.stop();

        Optional<VenRegistration> previousActive = findActiveRegistration();

        RegistrationResult result = registerNew();

        previousActive.ifPresent(this::markCancelled);

        completeRegistration(result, true);
    }

    /**
     * VEN-initiated registration cancellation.
     */
    public void initiateCancelRegistration() {
        VenRegistration registration = requireActiveRegistration();
        OpenAdrSessionSnapshot session = sessionProvider.fromRegistration(registration);

        requireValidPersistedRegistration(registration);

        String requestId = newRequestId();

        OadrCancelPartyRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCancelPartyRegistrationBuilder(
                                requestId,
                                registration.getRegistrationId(),
                                registration.getVenId()
                        )
                        .build();

        log.info(
                "Sending VEN-initiated oadrCancelPartyRegistration. venId={}, registrationId={}",
                registration.getVenId(), registration.getRegistrationId()
        );

        Object response = transportService.send(
                OpenAdrOperations.CANCEL_PARTY_REGISTRATION,
                payload,
                session
        );

        if (!(response instanceof OadrCanceledPartyRegistrationType canceled)) {
            throw new IllegalStateException(
                    "Unexpected response to oadrCancelPartyRegistration. type="
                            + responseType(response)
            );
        }

        String responseCode = canceled.getEiResponse() == null
                ? null
                : canceled.getEiResponse().getResponseCode();

        if (!RESPONSE_OK.equals(responseCode)) {
            throw new IllegalStateException(
                    "VEN registration cancellation failed. code=%s, description=%s"
                            .formatted(
                                    responseCode, canceled.getEiResponse().getResponseDescription()
                            )
            );
        }

        markCancelled(registration);
        eventPoller.stop();

        log.info(
                "VEN registration cancelled. registrationId={}", registration.getRegistrationId()
        );
    }

    /**
     * Handles VTN-initiated re-registration received through oadrPoll.
     */
    public void handleRequestReregistration(OadrRequestReregistrationType request) {
        handleRequestReregistration(request, sessionProvider.current());
    }

    public void handleRequestReregistration(
            OadrRequestReregistrationType request,
            OpenAdrSessionSnapshot session
    ) {
        VenRegistration active = requireRegistration(session);

        log.info(
                "Received oadrRequestReregistration. requestedVenId={}, activeVenId={}, registrationId={}",
                request.getVenID(), active.getVenId(), active.getRegistrationId()
        );

        OadrResponseType acknowledgement = Oadr20bResponseBuilders
                .newOadr20bResponseBuilder(
                        "",
                        RESPONSE_CODE_OK,
                        active.getVenId()
                )
                .build();

        transportService.send(
                OpenAdrOperations.REGISTRATION_RESPONSE,
                acknowledgement,
                session
        );

        eventPoller.stop();

        RegistrationResult result = reregister(active);

        completeRegistration(result, false);
    }

    /**
     * Handles VTN-initiated registration cancellation received through
     * oadrPoll.
     */
    public void handleCancelPartyRegistration(
            OadrCancelPartyRegistrationType request
    ) {
        handleCancelPartyRegistration(request, sessionProvider.current());
    }

    public void handleCancelPartyRegistration(
            OadrCancelPartyRegistrationType request,
            OpenAdrSessionSnapshot session
    ) {
        Optional<VenRegistration> activeOptional = registrationFor(session);

        String requestRegistrationId = request.getRegistrationID();

        log.info(
                "Received oadrCancelPartyRegistration. registrationId={}",
                requestRegistrationId
        );

        boolean registrationMatches = activeOptional
                .map(VenRegistration::getRegistrationId)
                .filter(this::hasText)
                .map(requestRegistrationId::equals)
                .orElse(false);

        int responseCode = registrationMatches
                ? RESPONSE_CODE_OK
                : RESPONSE_CODE_INVALID_ID;

        String responseVenId = activeOptional
                .map(VenRegistration::getVenId)
                .filter(this::hasText)
                .orElseGet(() -> {
                    if (hasText(request.getVenID())) {
                        return request.getVenID();
                    }
                    return session.venId();
                });

        EiResponseType eiResponse = Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder(
                        request.getRequestID(),
                        responseCode
                )
                .build();

        OadrCanceledPartyRegistrationType response =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCanceledPartyRegistrationBuilder(
                                eiResponse,
                                requestRegistrationId,
                                responseVenId
                        )
                        .build();

        transportService.send(
                OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE,
                response,
                session
        );

        if (!registrationMatches) {
            log.warn(
                    "Cannot cancel registration: registrationID does not " +
                            "match the active registration. requested={}",
                    requestRegistrationId
            );
            return;
        }

        VenRegistration active = activeOptional.orElseThrow();

        markCancelled(active);
        eventPoller.stop();

        log.info(
                "VTN-initiated registration cancellation completed. " +
                        "registrationId={}",
                requestRegistrationId
        );
    }

    private VenRegistration saveRegistration(
            OadrCreatedPartyRegistrationType response, VenRegistration existing
    ) {
        VenRegistration registration =
                existing != null
                        ? existing
                        : new VenRegistration();

        String venId = firstNonBlank(
                response.getVenID(),
                existing == null ? null : existing.getVenId(),
                properties.getVen().getId()
        );

        String requestedPollFrequency = extractRequestedPollFrequency(response);

        registration.setVenId(venId);
        registration.setVtnId(response.getVtnID());
        registration.setRegistrationId(response.getRegistrationID());
        registration.setStatus(VenRegistrationStatus.REGISTERED);

        /*
         * If a re-registration response omits or contains an invalid polling
         * frequency, retain the value previously provided by the VTN.
         */
        if (hasText(requestedPollFrequency)) {
            registration.setRequestedPollFrequency(requestedPollFrequency);
        }

        if (registration.getRegisteredAt() == null) {
            registration.setRegisteredAt(nowUtc());
        }

        registration.setUpdatedAt(nowUtc());

        return registrationRepository.save(registration);
    }

    private void validateCreatedPartyRegistration(OadrCreatedPartyRegistrationType response) {
        if (response.getEiResponse() == null) {
            throw new IllegalStateException("oadrCreatedPartyRegistration does not contain eiResponse");
        }

        String responseCode = response.getEiResponse().getResponseCode();

        if (!RESPONSE_OK.equals(responseCode)) {
            throw new IllegalStateException(
                    "VEN registration failed. code=%s, description=%s"
                            .formatted(responseCode, response.getEiResponse().getResponseDescription())
            );
        }

        if (!hasText(response.getRegistrationID())) {
            throw new IllegalStateException(
                    "Successful oadrCreatedPartyRegistration does not contain registrationID"
            );
        }

        if (!hasText(response.getVtnID())) {
            log.warn("Successful oadrCreatedPartyRegistration does not contain vtnID");
        }
    }

    /**
     * Reads the polling frequency assigned by the VTN.
     * The Original ISO-8601 value is persisted, for example, PT10S or PT1M.
     */
    private String extractRequestedPollFrequency(OadrCreatedPartyRegistrationType response) {
        if (response.getOadrRequestedOadrPollFreq() == null) {
            log.error(
                    "Protocol error: oadrCreatedPartyRegistration does not " +
                            "contain oadrRequestedOadrPollFreq for HTTP Pull"
            );
            return null;
        }

        String value = response
                .getOadrRequestedOadrPollFreq()
                .getDuration();

        if (!hasText(value)) {
            log.error(
                    "Protocol error: oadrRequestedOadrPollFreq duration " +
                            "is empty"
            );
            return null;
        }

        try {
            Duration parsed = Duration.parse(value);

            if (parsed.isZero() || parsed.isNegative()) {
                log.error(
                        "Invalid oadrRequestedOadrPollFreq={}: duration " +
                                "must be positive",
                        value
                );
                return null;
            }

            return value;
        } catch (RuntimeException exception) {
            log.error(
                    "Invalid oadrRequestedOadrPollFreq={}. " +
                            "The value will not be persisted.",
                    value,
                    exception
            );
            return null;
        }
    }

    private Duration resolvePollInterval(
            VenRegistration registration
    ) {
        String persistedValue =
                registration.getRequestedPollFrequency();

        if (hasText(persistedValue)) {
            try {
                Duration parsed = Duration.parse(persistedValue);

                if (!parsed.isZero() && !parsed.isNegative()) {
                    return parsed;
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "Cannot parse persisted requestedPollFrequency={}",
                        persistedValue,
                        exception
                );
            }
        }

        Duration fallback = defaultPollInterval();

        log.warn(
                "Using configured polling interval fallback={}",
                fallback
        );

        return fallback;
    }

    private void runPostRegistrationFlow(VenRegistration registration) {
        OpenAdrSessionSnapshot session = sessionProvider.fromRegistration(registration);
        OadrRegisteredReportType registeredReport =
                reportService.registerReportingCapabilities(session);

        reportRequestHandler.handleRegisteredReport(
                registeredReport,
                session
        );

        requestAllEvents(session);
    }

    private void requestAllEvents(OpenAdrSessionSnapshot session) {
        String venId = session.venId();
        String requestId = newRequestId();

        OadrRequestEventType requestEvent =
                Oadr20bEiEventBuilders
                        .newOadrRequestEventBuilder(
                                venId, requestId
                        )
                        .withReplyLimit(REQUEST_EVENT_REPLY_LIMIT)
                        .build();

        log.info("Sending oadrRequestEvent. venId={}, requestId={}", venId, requestId);

        Object response = transportService.send(
                OpenAdrOperations.REQUEST_EVENT,
                requestEvent,
                session
        );

        if (response instanceof OadrDistributeEventType distributeEvent) {
            log.info("Received {} event(s) from oadrRequestEvent", distributeEvent.getOadrEvent().size());

            drEventHandler.handle(distributeEvent, session);
            return;
        }

        if (response instanceof OadrResponseType oadrResponse) {
            log.info(
                    "oadrRequestEvent returned response code={}",
                    oadrResponse.getEiResponse() == null
                            ? null
                            : oadrResponse.getEiResponse()
                            .getResponseCode()
            );
            return;
        }

        log.warn(
                "Unexpected oadrRequestEvent response. type={}",
                responseType(response)
        );
    }

    private void eraseReportAndOptData() {
        venReportRepository.deleteAll();
        optScheduleRepository.deleteAll();

        log.info(
                "Cleared VEN report and opt schedule state for " +
                        "the new registration instance"
        );
    }

    private Optional<VenRegistration> findActiveRegistration() {
        return registrationRepository.findFirstByStatusOrderByUpdatedAtDesc(
                        VenRegistrationStatus.REGISTERED
                );
    }

    private VenRegistration requireActiveRegistration() {
        return findActiveRegistration()
                .orElseThrow(() -> new IllegalStateException(
                        "Active VEN registration was not found"
                ));
    }

    private Optional<VenRegistration> registrationFor(
            OpenAdrSessionSnapshot session
    ) {
        if (session.registrationEntityId() == null) {
            return Optional.empty();
        }
        return registrationRepository.findById(session.registrationEntityId());
    }

    private VenRegistration requireRegistration(OpenAdrSessionSnapshot session) {
        return registrationFor(session)
                .orElseThrow(() -> new IllegalStateException(
                        "Registration from OpenADR session snapshot was not found"
                ));
    }

    private void requireValidPersistedRegistration(VenRegistration registration) {
        if (!hasText(registration.getVenId())) {
            throw new IllegalStateException(
                    "Persisted VEN registration does not contain venID"
            );
        }

        if (!hasText(registration.getRegistrationId())) {
            throw new IllegalStateException(
                    "Persisted VEN registration does not contain registrationID"
            );
        }
    }

    private void markCancelled(VenRegistration registration) {
        registration.setStatus(VenRegistrationStatus.CANCELLED);
        registration.setUpdatedAt(nowUtc());

        registrationRepository.save(registration);
    }

    private Duration defaultPollInterval() {
        return Duration.ofSeconds(
                properties.getTransport().getPollIntervalSeconds()
        );
    }

    private String responseCode(
            OadrCreatedPartyRegistrationType response
    ) {
        return response.getEiResponse() == null
                ? null
                : response.getEiResponse().getResponseCode();
    }

    private String responseType(Object response) {
        return response == null
                ? "null"
                : response.getClass().getName();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }

        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private Instant nowUtc() {
        return Instant.now();
    }

    private record RegistrationResult(
            VenRegistration registration,
            boolean newRegistrationInstance
    ) {
    }
}
