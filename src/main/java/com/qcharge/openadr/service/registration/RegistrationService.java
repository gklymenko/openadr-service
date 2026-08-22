package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.ApiMessage;
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
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.repository.OptScheduleRepository;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.repository.VenReportRepository;
import com.qcharge.openadr.service.event.protocol.EventProtocolAdapter;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.utility.RequestUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.qcharge.openadr.LogMessage.COMPLETED_OADR_QUERY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.FORCE_NEW_REGISTRATION;
import static com.qcharge.openadr.LogMessage.SEND_CANCEL_PARTY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.SEND_CREATE_PARTY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.SEND_OADR_QUERY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.VEN_REGISTRATION_CANCEL_COMPLETED;
import static com.qcharge.openadr.LogMessage.VEN_REGISTRATION_COMPLETED;
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
    private final EventProtocolAdapter eventProtocolAdapter;
    private final OpenAdrSessionProvider sessionProvider;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Optional discovery call. Its response must never be used as the source
     * of venID or registrationID for an active registration.
     */
    public void queryRegistration() {
        OpenAdrSessionSnapshot session = sessionProvider.current();
        String requestId = RequestUtils.newRequestId();

        OadrQueryRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bQueryRegistrationBuilder(requestId)
                        .build();

        log.info(SEND_OADR_QUERY_REGISTRATION, requestId);

        transportService.send(
                OpenAdrOperations.QUERY_REGISTRATION, payload, session
        );

        log.info(COMPLETED_OADR_QUERY_REGISTRATION);
    }

    public OpenAdrSessionSnapshot performRegistration() {
        RegistrationResult result = registerNew();
        return completeRegistration(result);
    }

    public OpenAdrSessionSnapshot performReregistration(OpenAdrSessionSnapshot session) {
        VenRegistration venRegistration = requireRegistration(session);
        RegistrationResult result = reregister(venRegistration);
        return completeRegistration(result);
    }

    /**
     * Creates a completely new registration request without registrationID.
     */
    private RegistrationResult registerNew() {
        OpenAdrSessionSnapshot session = sessionProvider.bootstrap();

        OadrCreatedPartyRegistrationType response = sendCreatePartyRegistration(session);

        VenRegistration registration = saveRegistration(response, null);

        return new RegistrationResult(registration, true);
    }

    /**
     * Re-registers using the VEN ID and registration ID stored in the database.
     */
    private RegistrationResult reregister(VenRegistration existing) {
        OpenAdrSessionSnapshot session = sessionProvider.fromRegistration(existing);

        String previousRegistrationId = existing.getRegistrationId();

        OadrCreatedPartyRegistrationType response = sendCreatePartyRegistration(session);

        String receivedRegistrationId = response.getRegistrationID();

        boolean newRegistrationInstance = !Objects.equals(previousRegistrationId, receivedRegistrationId);

        VenRegistration registration = saveRegistration(response, existing);

        return new RegistrationResult(registration, newRegistrationInstance);
    }

    public OpenAdrSessionSnapshot performForcedNewRegistration() {
        log.warn(FORCE_NEW_REGISTRATION);

        Optional<VenRegistration> previousActive = findActiveRegistration();

        RegistrationResult newRegistration = registerNew();

        previousActive.ifPresent(this::markCancelled);

        return completeRegistration(newRegistration);
    }

    private OadrCreatedPartyRegistrationType sendCreatePartyRegistration(OpenAdrSessionSnapshot session) {
        String venId = session.venId();
        String registrationId = session.registrationId();
        String requestId = RequestUtils.newRequestId();

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

        log.info(SEND_CREATE_PARTY_REGISTRATION, venId, requestId, StringUtils.hasText(registrationId));

        return transportService.send(
                OpenAdrOperations.CREATE_PARTY_REGISTRATION, payload, session
        );
    }

    /**
     * Completes either new registration or re-registration.
     * For a new registration instance, old reports/options are invalidated and
     * the full metadata/event bootstrap is performed.
     * For an unchanged re-registration instance, polling is simply resumed.
     */
    private OpenAdrSessionSnapshot completeRegistration(RegistrationResult result) {
        VenRegistration registration = result.registration();

        boolean runFullBootstrap = result.newRegistrationInstance();

        OpenAdrSessionSnapshot registeredSession = sessionProvider.fromRegistration(registration);

        if (runFullBootstrap) {
            eraseReportAndOptData();
            eventPublisher.publishEvent(new PostRegistrationBootstrapEvent(registeredSession));
        }

        log.info(
                VEN_REGISTRATION_COMPLETED, registration.getVenId(), registration.getVtnId(),
                registration.getRegistrationId(), runFullBootstrap, registeredSession.pollFrequency()
        );

        return registeredSession;
    }

    public void performCancelRegistration(OpenAdrSessionSnapshot session) {
        VenRegistration registration = requireRegistration(session);

        String requestId = RequestUtils.newRequestId();

        OadrCancelPartyRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCancelPartyRegistrationBuilder(
                                requestId,
                                registration.getRegistrationId(),
                                registration.getVenId()
                        )
                        .build();

        log.info(SEND_CANCEL_PARTY_REGISTRATION, registration.getVenId(), registration.getRegistrationId());

        transportService.send(
                OpenAdrOperations.CANCEL_PARTY_REGISTRATION, payload, session
        );

        markCancelled(registration);

        log.info(VEN_REGISTRATION_CANCEL_COMPLETED, registration.getRegistrationId());
    }

    public void acknowledgeRequestReregistration(
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
                OpenAdrOperations.REGISTRATION_RESPONSE, acknowledgement, session
        );
    }

    public boolean acknowledgeCancelPartyRegistration(
            OadrCancelPartyRegistrationType request, OpenAdrSessionSnapshot session
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
            return false;
        }

        return true;
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
        String requestedPollFrequency = extractRequestedPollFrequency(response);

        registration.setVenId(response.getVenID());
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

    private Optional<VenRegistration> registrationFor(OpenAdrSessionSnapshot session) {
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

    private void markCancelled(VenRegistration registration) {
        registration.setStatus(VenRegistrationStatus.CANCELLED);
        registration.setUpdatedAt(nowUtc());

        registrationRepository.save(registration);
    }

    private String responseType(Object response) {
        return response == null
                ? "null"
                : response.getClass().getName();
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
