package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiEventBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiOptBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiRegisterPartyBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bResponseBuilders;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Creates the protocol reply required by the inbound OpenADR payload type.
 */
@Component
public class OpenAdrReplyFactory {

    public Optional<OpenAdrReply<?, ?>> createApplicationErrorReply(
            Object inboundPayload,
            String venId,
            OpenAdrApplicationException error
    ) {
        String requestId = normalize(error.getRequestId());
        String description = normalizeDescription(error);
        int responseCode = error.getResponseCode();

        return switch (inboundPayload) {
            case OadrDistributeEventType ignored -> Optional.of(createdEvent(
                    requestId, responseCode, description, venId
            ));
            case OadrCreateReportType ignored -> Optional.of(createdReport(
                    requestId, responseCode, description, venId
            ));
            case OadrRegisterReportType ignored -> Optional.of(registeredReport(
                    requestId, responseCode, description, venId
            ));
            case OadrCancelReportType ignored -> Optional.of(canceledReport(
                    requestId, responseCode, description, venId
            ));
            case OadrUpdateReportType ignored -> Optional.of(updatedReport(
                    requestId, responseCode, description, venId
            ));
            case OadrCreateOptType createOpt -> Optional.of(createdOpt(
                    requestId, responseCode, description, createOpt.getOptID()
            ));
            case OadrCancelOptType cancelOpt -> Optional.of(canceledOpt(
                    requestId, responseCode, description, cancelOpt.getOptID()
            ));
            case OadrCancelPartyRegistrationType cancelRegistration ->
                    Optional.of(canceledPartyRegistration(
                            requestId,
                            responseCode,
                            description,
                            cancelRegistration.getRegistrationID(),
                            venId
                    ));
            case OadrRequestReregistrationType ignored -> Optional.of(response(
                    requestId, responseCode, description, venId
            ));
            default -> Optional.empty();
        };
    }

    private OpenAdrReply<OadrCreatedEventType, OadrResponseType> createdEvent(
            String requestId, int responseCode, String description, String venId
    ) {
        EiResponseType eiResponse = eiResponse(requestId, responseCode, description);
        OadrCreatedEventType payload = Oadr20bEiEventBuilders
                .newCreatedEventBuilder(eiResponse, venId)
                .build();
        return new OpenAdrReply<>(OpenAdrOperations.CREATED_EVENT, payload);
    }

    private OpenAdrReply<OadrCreatedReportType, OadrResponseType> createdReport(
            String requestId, int responseCode, String description, String venId
    ) {
        OadrCreatedReportType payload = Oadr20bEiReportBuilders
                .newOadr20bCreatedReportBuilder(requestId, responseCode, venId)
                .withResponseDescription(description)
                .build();
        return new OpenAdrReply<>(OpenAdrOperations.CREATED_REPORT_RESPONSE, payload);
    }

    private OpenAdrReply<OadrRegisteredReportType, OadrResponseType> registeredReport(
            String requestId, int responseCode, String description, String venId
    ) {
        OadrRegisteredReportType payload = Oadr20bEiReportBuilders
                .newOadr20bRegisteredReportBuilder(requestId, responseCode, venId)
                .build();
        payload.getEiResponse().setResponseDescription(description);
        return new OpenAdrReply<>(OpenAdrOperations.REGISTERED_REPORT_RESPONSE, payload);
    }

    private OpenAdrReply<OadrCanceledReportType, OadrResponseType> canceledReport(
            String requestId, int responseCode, String description, String venId
    ) {
        OadrCanceledReportType payload = Oadr20bEiReportBuilders
                .newOadr20bCanceledReportBuilder(requestId, responseCode, venId)
                .withResponseDescription(description)
                .build();
        return new OpenAdrReply<>(OpenAdrOperations.CANCELED_REPORT_RESPONSE, payload);
    }

    private OpenAdrReply<OadrUpdatedReportType, OadrResponseType> updatedReport(
            String requestId, int responseCode, String description, String venId
    ) {
        OadrUpdatedReportType payload = Oadr20bEiReportBuilders
                .newOadr20bUpdatedReportBuilder(requestId, responseCode, venId)
                .withResponseDescription(description)
                .build();
        return new OpenAdrReply<>(OpenAdrOperations.UPDATED_REPORT_RESPONSE, payload);
    }

    private OpenAdrReply<OadrCreatedOptType, OadrResponseType> createdOpt(
            String requestId, int responseCode, String description, String optId
    ) {
        OadrCreatedOptType payload = Oadr20bEiOptBuilders
                .newOadr20bCreatedOptBuilder(requestId, responseCode, optId)
                .withResponseDescription(description)
                .build();
        return new OpenAdrReply<>(OpenAdrOperations.CREATED_OPT_RESPONSE, payload);
    }

    private OpenAdrReply<OadrCanceledOptType, OadrResponseType> canceledOpt(
            String requestId, int responseCode, String description, String optId
    ) {
        OadrCanceledOptType payload = Oadr20bEiOptBuilders
                .newOadr20bCanceledOptBuilder(requestId, responseCode, optId)
                .build();
        payload.getEiResponse().setResponseDescription(description);
        return new OpenAdrReply<>(OpenAdrOperations.CANCELED_OPT_RESPONSE, payload);
    }

    private OpenAdrReply<OadrCanceledPartyRegistrationType, OadrResponseType>
            canceledPartyRegistration(
                    String requestId,
                    int responseCode,
                    String description,
                    String registrationId,
                    String venId
            ) {
        EiResponseType eiResponse = eiResponse(requestId, responseCode, description);
        OadrCanceledPartyRegistrationType payload =
                Oadr20bEiRegisterPartyBuilders
                        .newOadr20bCanceledPartyRegistrationBuilder(
                                eiResponse, registrationId, venId
                        )
                        .build();
        return new OpenAdrReply<>(
                OpenAdrOperations.CANCELED_PARTY_REGISTRATION_RESPONSE,
                payload
        );
    }

    private OpenAdrReply<OadrResponseType, OadrResponseType> response(
            String requestId, int responseCode, String description, String venId
    ) {
        OadrResponseType payload = Oadr20bResponseBuilders
                .newOadr20bResponseBuilder(requestId, responseCode, venId)
                .withDescription(description)
                .build();
        return new OpenAdrReply<>(OpenAdrOperations.REGISTRATION_RESPONSE, payload);
    }

    private EiResponseType eiResponse(
            String requestId, int responseCode, String description
    ) {
        return Oadr20bResponseBuilders
                .newOadr20bEiResponseBuilder(requestId, responseCode)
                .withDescription(description)
                .build();
    }

    private String normalizeDescription(OpenAdrApplicationException error) {
        if (error.getResponseDescription() != null
                && !error.getResponseDescription().isBlank()) {
            return error.getResponseDescription();
        }
        return "OpenADR application error " + error.getResponseCode();
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
