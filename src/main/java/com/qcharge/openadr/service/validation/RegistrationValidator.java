package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrProfiles;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrTransportType;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.hasText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.invalidData;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.invalidId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.isSuccess;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.missing;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.require;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireEiResponse;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireMatchingId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateOptionalId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateRequestIdEcho;

@Component
public class RegistrationValidator implements OpenAdrExchangeValidator {

    @Override
    public boolean supports(OpenAdrExchangeContext<?, ?> context) {
        return context.operation() == OpenAdrOperations.QUERY_REGISTRATION
                || context.operation() == OpenAdrOperations.CREATE_PARTY_REGISTRATION
                || context.operation() == OpenAdrOperations.CANCEL_PARTY_REGISTRATION;
    }

    @Override
    public void validate(OpenAdrExchangeContext<?, ?> context) {
        switch (context.request()) {
            case OadrQueryRegistrationType request ->
                    validateQuery(
                            request,
                            (OadrCreatedPartyRegistrationType) context.response(),
                            context.session()
                    );
            case OadrCreatePartyRegistrationType request ->
                    validateCreate(
                            request,
                            (OadrCreatedPartyRegistrationType) context.response(),
                            context.session()
                    );
            case OadrCancelPartyRegistrationType request ->
                    validateCancel(request, (OadrCanceledPartyRegistrationType) context.response());
            default -> throw new IllegalArgumentException(
                    "Unsupported registration request type: " + context.request().getClass().getName()
            );
        }
    }

    private void validateQuery(
            OadrQueryRegistrationType request,
            OadrCreatedPartyRegistrationType response,
            OpenAdrSessionSnapshot session
    ) {
        EiResponseType eiResponse = requireEiResponse(
                response.getEiResponse(),
                "oadrCreatedPartyRegistration",
                request.getRequestID()
        );
        validateRequestIdEcho(request.getRequestID(), eiResponse, "oadrCreatedPartyRegistration");

        if (!isSuccess(eiResponse)) {
            return;
        }

        validateVtnId(response.getVtnID(), request.getRequestID(), session);
        validateProfiles(response, null, null, request.getRequestID());

        if (!session.registered()) {
            if (hasText(response.getVenID()) || hasText(response.getRegistrationID())) {
                throw invalidId(
                        "oadrCreatedPartyRegistration.venID/registrationID",
                        "absent for unregistered VEN",
                        response.getVenID() + "/" + response.getRegistrationID(),
                        request.getRequestID()
                );
            }
            return;
        }

        validateOptionalId(
                "oadrCreatedPartyRegistration.venID",
                session.venId(),
                response.getVenID(),
                request.getRequestID()
        );
        validateOptionalId(
                "oadrCreatedPartyRegistration.registrationID",
                session.registrationId(),
                response.getRegistrationID(),
                request.getRequestID()
        );
    }

    private void validateCreate(
            OadrCreatePartyRegistrationType request,
            OadrCreatedPartyRegistrationType response,
            OpenAdrSessionSnapshot session
    ) {
        EiResponseType eiResponse = requireEiResponse(
                response.getEiResponse(),
                "oadrCreatedPartyRegistration",
                request.getRequestID()
        );
        validateRequestIdEcho(request.getRequestID(), eiResponse, "oadrCreatedPartyRegistration");

        if (!isSuccess(eiResponse)) {
            return;
        }

        validateVtnId(response.getVtnID(), request.getRequestID(), session);
        requireText(
                response.getVenID(),
                "oadrCreatedPartyRegistration.venID",
                request.getRequestID()
        );
        requireText(
                response.getRegistrationID(),
                "oadrCreatedPartyRegistration.registrationID",
                request.getRequestID()
        );
        validateProfiles(
                response,
                request.getOadrProfileName(),
                request.getOadrTransportName(),
                request.getRequestID()
        );

        if (hasText(request.getRegistrationID())) {
            requireMatchingId(
                    "oadrCreatedPartyRegistration.registrationID",
                    request.getRegistrationID(),
                    response.getRegistrationID(),
                    request.getRequestID()
            );
            requireMatchingId(
                    "oadrCreatedPartyRegistration.venID",
                    request.getVenID(),
                    response.getVenID(),
                    request.getRequestID()
            );
        }

        if (Boolean.TRUE.equals(request.isOadrHttpPullModel())) {
            validatePollFrequency(response, request.getRequestID());
        }
    }

    private void validateCancel(
            OadrCancelPartyRegistrationType request, OadrCanceledPartyRegistrationType response
    ) {
        EiResponseType eiResponse = requireEiResponse(
                response.getEiResponse(),
                "oadrCanceledPartyRegistration",
                request.getRequestID()
        );
        validateRequestIdEcho(request.getRequestID(), eiResponse, "oadrCanceledPartyRegistration");

        if (!isSuccess(eiResponse)) {
            return;
        }

        requireMatchingId(
                "oadrCanceledPartyRegistration.registrationID",
                request.getRegistrationID(),
                response.getRegistrationID(),
                request.getRequestID()
        );
        validateOptionalId(
                "oadrCanceledPartyRegistration.venID",
                request.getVenID(),
                response.getVenID(),
                request.getRequestID()
        );
    }

    private void validateVtnId(
            String actualVtnId,
            String requestId,
            OpenAdrSessionSnapshot session
    ) {
        String expectedVtnId = session.vtnId();
        requireText(actualVtnId, "oadrCreatedPartyRegistration.vtnID", requestId);

        if (hasText(expectedVtnId)) {
            requireMatchingId(
                    "oadrCreatedPartyRegistration.vtnID",
                    expectedVtnId,
                    actualVtnId,
                    requestId
            );
        }
    }

    private void validateProfiles(
            OadrCreatedPartyRegistrationType response,
            String requestedProfile,
            OadrTransportType requestedTransport,
            String requestId
    ) {
        OadrProfiles profiles = response.getOadrProfiles();
        require(
                profiles != null && !profiles.getOadrProfile().isEmpty(),
                "oadrCreatedPartyRegistration.oadrProfiles",
                requestId
        );

        for (OadrProfiles.OadrProfile profile : profiles.getOadrProfile()) {
            requireText(
                    profile == null ? null : profile.getOadrProfileName(),
                    "oadrCreatedPartyRegistration.oadrProfiles.oadrProfileName",
                    requestId
            );
            require(
                    profile.getOadrTransports() != null
                            && !profile.getOadrTransports().getOadrTransport().isEmpty(),
                    "oadrCreatedPartyRegistration.oadrProfiles.oadrTransports",
                    requestId
            );
        }

        if (!hasText(requestedProfile) || requestedTransport == null) {
            return;
        }

        boolean supported = profiles.getOadrProfile().stream()
                .filter(profile -> requestedProfile.equals(profile.getOadrProfileName()))
                .filter(profile -> profile.getOadrTransports() != null)
                .flatMap(profile -> profile.getOadrTransports().getOadrTransport().stream())
                .anyMatch(transport -> requestedTransport == transport.getOadrTransportName());

        if (!supported) {
            throw invalidData(
                    "VTN response does not advertise requested profile/transport: "
                            + requestedProfile + "/" + requestedTransport,
                    requestId
            );
        }
    }

    private void validatePollFrequency(
            OadrCreatedPartyRegistrationType response, String requestId
    ) {
        if (response.getOadrRequestedOadrPollFreq() == null) {
            throw missing("oadrCreatedPartyRegistration.oadrRequestedOadrPollFreq", requestId);
        }

        String value = requireText(
                response.getOadrRequestedOadrPollFreq().getDuration(),
                "oadrCreatedPartyRegistration.oadrRequestedOadrPollFreq.duration",
                requestId
        );

        try {
            Duration duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative()) {
                throw invalidData("oadrRequestedOadrPollFreq must be positive", requestId);
            }
        } catch (RuntimeException exception) {
            throw invalidData("Invalid oadrRequestedOadrPollFreq: " + value, requestId);
        }
    }
}
