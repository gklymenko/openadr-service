package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.ApiMessage;
import com.qcharge.openadr.LogMessage;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
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
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.report.ReportRequestHandler;
import com.qcharge.openadr.service.report.ReportService;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.qcharge.openadr.LogMessage.FAILED_VEN_BOOTSTRAP;
import static com.qcharge.openadr.LogMessage.START_VEN_BOOTSTRAP;
import static com.qcharge.openadr.exceptions.OpenADRResponseCode.INVALID_ID;
import static com.qcharge.openadr.exceptions.OpenADRResponseCode.OK;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final OpenAdrProperties properties;
    private final VenRegistrationRepository registrationRepository;
    private final VenReportRepository venReportRepository;
    private final OptScheduleRepository optScheduleRepository;
    private final VtnTransportService transportService;
    private final ReportService reportService;
    private final ReportRequestHandler reportRequestHandler;
    private final EventProtocolAdapter eventProtocolAdapter;
    private final OpenAdrSessionProvider sessionProvider;
    private final OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    /**
     * Returns the VEN ID assigned by the VTN for the current active registration.
     * Configured VEN ID is used only before the first successful registration.
     */
    public String currentVenId() {
        return lifecycleCoordinator.currentSession().venId();
    }

    /**
     * Startup flow:
     * 1. Optionally, query supported registration capabilities.
     * 2. If no active registration exists, perform new registration.
     * 3. If active registration exists, perform re-registration using persisted IDs.
     * 4. Start polling using the frequency returned by the VTN or persisted earlier.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info(START_VEN_BOOTSTRAP, properties.getVen().getId());

        try {
            lifecycleCoordinator.bootstrap();
        } catch (Exception exception) {
            log.error(FAILED_VEN_BOOTSTRAP, exception);
        }
    }

    public OpenAdrSessionSnapshot performBootstrapRegistration() {
        if (properties.getVen().isQueryRegistrationOnStartup()) {
            queryRegistration();
        }

        Optional<VenRegistration> activeRegistration = findActiveRegistration();

        if (activeRegistration.isEmpty()) {
            log.info("No active VEN registration found. Performing new registration.");

            RegistrationResult result = registerNew();
            return completeRegistration(result, true);
        }

        VenRegistration existing = activeRegistration.get();

        log.info(
                "Active registration found. Performing re-registration. venId={}, registrationId={}",
                existing.getVenId(), existing.getRegistrationId()
        );

        RegistrationResult result = reregister(existing);
        return completeRegistration(result, false);
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

    public OpenAdrSessionSnapshot performRegistration() {
        Optional<VenRegistration> active = findActiveRegistration();

        if (active.isPresent()) {
            RegistrationResult result = reregister(active.get());
            return completeRegistration(result, false);
        }

        RegistrationResult result = registerNew();
        return completeRegistration(result, true);
    }

    public OpenAdrSessionSnapshot performReregistration(
            OpenAdrSessionSnapshot session
    ) {
        RegistrationResult result = reregister(requireRegistration(session));
        return completeRegistration(result, false);
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

        if (StringUtils.hasText(registrationId)) {
            builder.withRegistrationId(registrationId);
        }

        if (StringUtils.hasText(properties.getVen().getName())) {
            builder.withOadrVenName(properties.getVen().getName());
        }

        OadrCreatePartyRegistrationType payload = builder.build();

        log.info(
                "Sending oadrCreatePartyRegistration. venId={}, requestId={}, reRegistration={}",
                venId, requestId, StringUtils.hasText(registrationId)
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
    private OpenAdrSessionSnapshot completeRegistration(
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

        OpenAdrSessionSnapshot registeredSession =
                sessionProvider.fromRegistration(registration);

        log.info(
                "VEN registration flow completed. " +
                        "venId={}, vtnId={}, registrationId={}, " +
                        "newRegistrationInstance={}, pollInterval={}",
                registration.getVenId(),
                registration.getVtnId(),
                registration.getRegistrationId(),
                runFullBootstrap,
                registeredSession.pollFrequency()
        );

        return registeredSession;
    }

    /**
     * Test-specific operation that deliberately sends a new registration
     * without registrationID even if an active registration exists.
     */
    public void initiateForcedNewRegistration() {
        lifecycleCoordinator.forceNewRegistration();
    }

    public OpenAdrSessionSnapshot performForcedNewRegistration() {
        log.warn("Forcing a new registration without registrationID");

        Optional<VenRegistration> previousActive = findActiveRegistration();

        RegistrationResult result = registerNew();

        previousActive.ifPresent(this::markCancelled);

        return completeRegistration(result, true);
    }

    public void performCancelRegistration(OpenAdrSessionSnapshot session) {
        VenRegistration registration = requireRegistration(session);

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

        if (!OpenADRResponseCode.matches(OK, responseCode)) {
            throw new IllegalStateException(
                    ApiMessage.FAILED_CANCEL_VEN_REGISTRATION.format(
                            responseCode, canceled.getEiResponse().getResponseDescription()
                    )
            );
        }

        markCancelled(registration);

        log.info(
                "VEN registration cancelled. registrationId={}", registration.getRegistrationId()
        );
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
                        OK,
                        active.getVenId()
                )
                .build();

        transportService.send(
                OpenAdrOperations.REGISTRATION_RESPONSE,
                acknowledgement,
                session
        );

        lifecycleCoordinator.reregister(session);
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
                .filter(StringUtils::hasText)
                .map(requestRegistrationId::equals)
                .orElse(false);

        int responseCode = registrationMatches ? OK : INVALID_ID;

        String responseVenId = activeOptional
                .map(VenRegistration::getVenId)
                .filter(StringUtils::hasText)
                .orElseGet(() -> {
                    if (StringUtils.hasText(request.getVenID())) {
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

        lifecycleCoordinator.acceptRemoteCancellation(session);

        log.info(
                "VTN-initiated registration cancellation completed. " +
                        "registrationId={}",
                requestRegistrationId
        );
    }

    public void performRemoteCancellation(OpenAdrSessionSnapshot session) {
        markCancelled(requireRegistration(session));
    }

    private VenRegistration saveRegistration(
            OadrCreatedPartyRegistrationType response, VenRegistration existing
    ) {
        VenRegistration registration =
                existing != null
                        ? existing
                        : new VenRegistration();


        String venId = org.apache.commons.lang3.StringUtils.firstNonBlank(
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
        if (StringUtils.hasText(requestedPollFrequency)) {
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

        if (!OpenADRResponseCode.matches(OK, responseCode)) {
            throw new IllegalStateException(
                    "VEN registration failed. code=%s, description=%s"
                            .formatted(responseCode, response.getEiResponse().getResponseDescription())
            );
        }

        if (!StringUtils.hasText(response.getRegistrationID())) {
            throw new IllegalStateException(
                    "Successful oadrCreatedPartyRegistration does not contain registrationID"
            );
        }

        if (!StringUtils.hasText(response.getVtnID())) {
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


        if (!StringUtils.hasText(value)) {
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
        requestAllEvents(session, newRequestId());
    }

    public void requestAllEvents(@NotNull OpenAdrSessionSnapshot session, @NotBlank String requestId) {
        String venId = session.venId();

        OadrRequestEventType requestEvent = Oadr20bEiEventBuilders
                .newOadrRequestEventBuilder(venId, requestId)
                .build();

        log.info("Sending oadrRequestEvent. venId={}, requestId={}", venId, requestId);

        Object response = transportService.send(
                OpenAdrOperations.REQUEST_EVENT, requestEvent, session
        );

        if (response instanceof OadrDistributeEventType distributeEvent) {
            log.info("Received {} event(s) from oadrRequestEvent", distributeEvent.getOadrEvent().size());

            eventProtocolAdapter.receive(distributeEvent, session);
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
        if (!StringUtils.hasText(registration.getVenId())) {
            throw new IllegalStateException(
                    "Persisted VEN registration does not contain venID"
            );
        }

        if (!StringUtils.hasText(registration.getRegistrationId())) {
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
