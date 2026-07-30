package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;

import java.util.Set;

import static com.qcharge.openadr.service.transport.ResponseBodyPolicy.OPTIONAL;
import static com.qcharge.openadr.service.transport.ResponseBodyPolicy.REQUIRED;

/**
 * Registry of outbound VEN operations and their transport contracts.
 */
public final class OpenAdrOperations {

    public static final OpenAdrOperation<OadrQueryRegistrationType, OadrCreatedPartyRegistrationType>
            QUERY_REGISTRATION = operation(
                    "queryRegistration",
                    Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE,
                    OadrQueryRegistrationType.class,
                    REQUIRED,
                    OadrCreatedPartyRegistrationType.class
            );

    public static final OpenAdrOperation<OadrCreatePartyRegistrationType, OadrCreatedPartyRegistrationType>
            CREATE_PARTY_REGISTRATION = operation(
                    "createPartyRegistration",
                    Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE,
                    OadrCreatePartyRegistrationType.class,
                    REQUIRED,
                    OadrCreatedPartyRegistrationType.class
            );

    public static final OpenAdrOperation<OadrCancelPartyRegistrationType, OadrCanceledPartyRegistrationType>
            CANCEL_PARTY_REGISTRATION = operation(
                    "cancelPartyRegistration",
                    Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE,
                    OadrCancelPartyRegistrationType.class,
                    REQUIRED,
                    OadrCanceledPartyRegistrationType.class
            );

    public static final OpenAdrOperation<OadrResponseType, OadrResponseType>
            REGISTRATION_RESPONSE = operation(
                    "registrationResponse",
                    Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE,
                    OadrResponseType.class,
                    OPTIONAL,
                    OadrResponseType.class
            );

    public static final OpenAdrOperation<OadrCanceledPartyRegistrationType, OadrResponseType>
            CANCELED_PARTY_REGISTRATION_RESPONSE = operation(
                    "canceledPartyRegistrationResponse",
                    Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE,
                    OadrCanceledPartyRegistrationType.class,
                    OPTIONAL,
                    OadrResponseType.class
            );

    public static final OpenAdrOperation<OadrPollType, Object> POLL = operation(
            "poll",
            Oadr20bUrlPath.OADR_POLL_SERVICE,
            OadrPollType.class,
            REQUIRED,
            OadrResponseType.class,
            OadrDistributeEventType.class,
            OadrCreateReportType.class,
            OadrRegisterReportType.class,
            OadrCancelReportType.class,
            OadrUpdateReportType.class,
            OadrCancelPartyRegistrationType.class,
            OadrRequestReregistrationType.class
    );

    public static final OpenAdrOperation<OadrRequestEventType, Object> REQUEST_EVENT = operation(
            "requestEvent",
            Oadr20bUrlPath.EI_EVENT_SERVICE,
            OadrRequestEventType.class,
            REQUIRED,
            OadrDistributeEventType.class,
            OadrResponseType.class
    );

    public static final OpenAdrOperation<OadrCreatedEventType, OadrResponseType> CREATED_EVENT = operation(
            "createdEvent",
            Oadr20bUrlPath.EI_EVENT_SERVICE,
            OadrCreatedEventType.class,
            OPTIONAL,
            OadrResponseType.class
    );

    public static final OpenAdrOperation<OadrRegisterReportType, OadrRegisteredReportType> REGISTER_REPORT = operation(
            "registerReport",
            Oadr20bUrlPath.EI_REPORT_SERVICE,
            OadrRegisterReportType.class,
            REQUIRED,
            OadrRegisteredReportType.class
    );

    public static final OpenAdrOperation<OadrRegisteredReportType, OadrResponseType>
            REGISTERED_REPORT_RESPONSE = reportResponse(
                    "registeredReportResponse",
                    OadrRegisteredReportType.class
            );

    public static final OpenAdrOperation<OadrCreatedReportType, OadrResponseType>
            CREATED_REPORT_RESPONSE = reportResponse(
                    "createdReportResponse",
                    OadrCreatedReportType.class
            );

    public static final OpenAdrOperation<OadrUpdateReportType, OadrUpdatedReportType> UPDATE_REPORT = operation(
            "updateReport",
            Oadr20bUrlPath.EI_REPORT_SERVICE,
            OadrUpdateReportType.class,
            REQUIRED,
            OadrUpdatedReportType.class
    );

    public static final OpenAdrOperation<OadrUpdatedReportType, OadrResponseType>
            UPDATED_REPORT_RESPONSE = reportResponse(
                    "updatedReportResponse",
                    OadrUpdatedReportType.class
            );

    public static final OpenAdrOperation<OadrCanceledReportType, OadrResponseType>
            CANCELED_REPORT_RESPONSE = reportResponse(
                    "canceledReportResponse",
                    OadrCanceledReportType.class
            );

    public static final OpenAdrOperation<OadrCreateOptType, OadrCreatedOptType> CREATE_OPT = operation(
            "createOpt",
            Oadr20bUrlPath.EI_OPT_SERVICE,
            OadrCreateOptType.class,
            REQUIRED,
            OadrCreatedOptType.class
    );

    public static final OpenAdrOperation<OadrCancelOptType, OadrCanceledOptType> CANCEL_OPT = operation(
            "cancelOpt",
            Oadr20bUrlPath.EI_OPT_SERVICE,
            OadrCancelOptType.class,
            REQUIRED,
            OadrCanceledOptType.class
    );

    public static final OpenAdrOperation<OadrCreatedOptType, OadrResponseType>
            CREATED_OPT_RESPONSE = optResponse("createdOptResponse", OadrCreatedOptType.class);

    public static final OpenAdrOperation<OadrCanceledOptType, OadrResponseType>
            CANCELED_OPT_RESPONSE = optResponse("canceledOptResponse", OadrCanceledOptType.class);

    private OpenAdrOperations() {
    }

    @SafeVarargs
    private static <Q, R> OpenAdrOperation<Q, R> operation(
            String name,
            String endpoint,
            Class<Q> requestType,
            ResponseBodyPolicy bodyPolicy,
            Class<? extends R>... responseTypes
    ) {
        return new OpenAdrOperation<>(
                name,
                endpoint,
                requestType,
                Set.of(responseTypes),
                bodyPolicy
        );
    }

    private static <Q> OpenAdrOperation<Q, OadrResponseType> reportResponse(
            String name,
            Class<Q> requestType
    ) {
        return operation(
                name,
                Oadr20bUrlPath.EI_REPORT_SERVICE,
                requestType,
                OPTIONAL,
                OadrResponseType.class
        );
    }

    private static <Q> OpenAdrOperation<Q, OadrResponseType> optResponse(
            String name,
            Class<Q> requestType
    ) {
        return operation(
                name,
                Oadr20bUrlPath.EI_OPT_SERVICE,
                requestType,
                OPTIONAL,
                OadrResponseType.class
        );
    }
}
