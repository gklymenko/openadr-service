package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.event.execution.EventExecutionCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.VtnTransportService;
import com.qcharge.openadr.utility.RequestUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.qcharge.openadr.LogMessage.COMPLETED_OADR_QUERY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.ERROR_CANCEL_PARTY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.FORCE_NEW_REGISTRATION;
import static com.qcharge.openadr.LogMessage.IGNORE_CANCEL_PARTY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.INVALID_ID_CANCEL_PARTY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.SEND_CANCEL_PARTY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.SEND_CREATE_PARTY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.SEND_OADR_QUERY_REGISTRATION;
import static com.qcharge.openadr.LogMessage.VEN_NEW_REGISTRATION_COMPLETED;
import static com.qcharge.openadr.LogMessage.VEN_REGISTRATION_CANCEL_COMPLETED;
import static com.qcharge.openadr.LogMessage.VEN_REREGISTRATION_COMPLETED;
import static com.qcharge.openadr.exceptions.OpenADRResponseCode.INVALID_ID;
import static com.qcharge.openadr.exceptions.OpenADRResponseCode.OK;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final OpenAdrProperties properties;
    private final VenRegistrationRepository registrationRepository;
    private final VenRegistrationStateService registrationStateService;
    private final VtnTransportService transportService;
    private final EventExecutionCoordinator eventExecutionCoordinator;
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

        transportService.send(OpenAdrOperations.QUERY_REGISTRATION, payload, session);

        log.info(COMPLETED_OADR_QUERY_REGISTRATION);
    }

    /**
     * Creates a completely new registration request without registrationID.
     */
    public OpenAdrSessionSnapshot performRegistration() {
        OpenAdrSessionSnapshot session = sessionProvider.bootstrap();

        OadrCreatedPartyRegistrationType response = sendCreatePartyRegistration(
                session, session.venId(), null
        );

        VenRegistration newRegistration = saveRegistration(response, Optional.empty());
        return completeRegistration(newRegistration);
    }

    public OpenAdrSessionSnapshot performReregistration(OpenAdrSessionSnapshot session) {
        VenRegistration existing = requireRegistration(session);
        VenRegistration reregistered = reregister(existing);

        OpenAdrSessionSnapshot registeredSession = sessionProvider.fromRegistration(reregistered);

        log.info(
                VEN_REREGISTRATION_COMPLETED, registeredSession.venId(), registeredSession.vtnId(),
                registeredSession.registrationId(), registeredSession.pollFrequency()
        );

        return registeredSession;
    }

    /**
     * Rule 406 / N1_0060: a new registration initiated while already registered must omit both venID and registrationID.
     */
    public OpenAdrSessionSnapshot performForcedNewRegistration() {
        log.warn(FORCE_NEW_REGISTRATION);

        Optional<VenRegistration> previousActive = findActiveRegistration();

        OpenAdrSessionSnapshot session = sessionProvider.bootstrap();

        OadrCreatedPartyRegistrationType response = sendCreatePartyRegistration(
                session, null, null
        );

        VenRegistration newRegistration =  saveRegistration(response, Optional.empty());

        previousActive.ifPresent(this::markCancelled);

        return completeRegistration(newRegistration);
    }

    /**
     * Re-registers using the VEN ID and registration ID stored in the database.
     */
    private VenRegistration reregister(VenRegistration existing) {
        OpenAdrSessionSnapshot session = sessionProvider.fromRegistration(existing);

        OadrCreatedPartyRegistrationType response = sendCreatePartyRegistration(
                session, session.venId(), session.registrationId()
        );

        return saveRegistration(response, Optional.of(existing));
    }

    private OadrCreatedPartyRegistrationType sendCreatePartyRegistration(
            OpenAdrSessionSnapshot session, String requestVenId, String requestRegistrationId
    ) {
        String requestId = RequestUtils.newRequestId();

        var builder = Oadr20bEiRegisterPartyBuilders
                .newOadr20bCreatePartyRegistrationBuilder(
                        requestId,
                        requestVenId,
                        properties.getVen().getProfile()
                )
                .withOadrTransportName(OadrTransportType.SIMPLE_HTTP)
                .withOadrTransportAddress(null)
                .withOadrReportOnly(false)
                .withOadrXmlSignature(false)
                .withOadrHttpPullModel(true);

        if (StringUtils.hasText(requestRegistrationId)) {
            builder.withRegistrationId(requestRegistrationId);
        }

        if (StringUtils.hasText(properties.getVen().getName())) {
            builder.withOadrVenName(properties.getVen().getName());
        }

        OadrCreatePartyRegistrationType payload = builder.build();

        log.info(SEND_CREATE_PARTY_REGISTRATION, requestVenId, requestId, StringUtils.hasText(requestRegistrationId));

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
    private OpenAdrSessionSnapshot completeRegistration(VenRegistration registration) {
        OpenAdrSessionSnapshot registeredSession = sessionProvider.fromRegistration(registration);

        registrationStateService.clearDependentRegistrationData();
        eventPublisher.publishEvent(new PostRegistrationBootstrapEvent(registeredSession));

        log.info(
                VEN_NEW_REGISTRATION_COMPLETED, registration.getVenId(), registration.getVtnId(),
                registration.getRegistrationId(), registeredSession.pollFrequency()
        );

        return registeredSession;
    }

    public void performCancelRegistration(OpenAdrSessionSnapshot session) {
        registrationStateService.beginCancellation(session);

        String requestId = RequestUtils.newRequestId();

        OadrCancelPartyRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCancelPartyRegistrationBuilder(
                                requestId,
                                session.registrationId(),
                                session.venId()
                        )
                        .build();

        log.info(SEND_CANCEL_PARTY_REGISTRATION, session.venId(), session.registrationId());

        transportService.send(
                OpenAdrOperations.CANCEL_PARTY_REGISTRATION, payload, session
        );

        completeCancellation(session);

        log.info(VEN_REGISTRATION_CANCEL_COMPLETED, session.registrationId());
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

    public RemoteCancellationDecision prepareRemoteCancellation(
            OadrCancelPartyRegistrationType request, OpenAdrSessionSnapshot session
    ) {
        String requestRegistrationId = request.getRegistrationID();

        if (!session.registered() || !registrationStateService.hasCancellableRegistration(session)) {
            log.warn(IGNORE_CANCEL_PARTY_REGISTRATION, requestRegistrationId);
            return RemoteCancellationDecision.IGNORED_NOT_REGISTERED;
        }

        boolean registrationMatches = Objects.equals(requestRegistrationId, session.registrationId())
                && (!StringUtils.hasText(request.getVenID())
                || Objects.equals(request.getVenID(), session.venId()));

        if (!registrationMatches) {
            sendCanceledPartyRegistration(request, session, INVALID_ID);
            log.warn(INVALID_ID_CANCEL_PARTY_REGISTRATION, requestRegistrationId);
            return RemoteCancellationDecision.REJECTED_INVALID_ID;
        }

        if (!registrationStateService.tryBeginCancellation(session)) {
            log.warn(ERROR_CANCEL_PARTY_REGISTRATION, requestRegistrationId);
            return RemoteCancellationDecision.IGNORED_NOT_REGISTERED;
        }

        return RemoteCancellationDecision.ACCEPTED;
    }

    public void acknowledgeRemoteCancellation(
            OadrCancelPartyRegistrationType request, OpenAdrSessionSnapshot session
    ) {
        sendCanceledPartyRegistration(request, session, OK);
    }

    private void sendCanceledPartyRegistration(
            OadrCancelPartyRegistrationType request, OpenAdrSessionSnapshot session,
            int responseCode
    ) {
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
                                request.getRegistrationID(),
                                session.venId()
                        )
                        .build();

        transportService.send(
                OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE, response, session
        );
    }

    public void completeCancellation(OpenAdrSessionSnapshot session) {
        eventExecutionCoordinator.clearDownstreamForRegistrationCancellation();
        registrationStateService.completeCancellation(session);
    }

    private VenRegistration saveRegistration(
            OadrCreatedPartyRegistrationType response, Optional<VenRegistration> existing
    ) {
        VenRegistration registration = existing.orElseGet(VenRegistration::new);
        registration.setVenId(response.getVenID());
        registration.setVtnId(response.getVtnID());
        registration.setRegistrationId(response.getRegistrationID());
        registration.setStatus(VenRegistrationStatus.REGISTERED);

        registration.setRequestedPollFrequency(
                response.getOadrRequestedOadrPollFreq().getDuration()
        );

        if (registration.getRegisteredAt() == null) {
            registration.setRegisteredAt(Instant.now());
        }

        registration.setUpdatedAt(Instant.now());

        return registrationRepository.save(registration);
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
        registration.setUpdatedAt(Instant.now());

        registrationRepository.save(registration);
    }

}
