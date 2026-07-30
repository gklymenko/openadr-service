package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.exceptions.OpenAdrHttpException;
import com.qcharge.openadr.exceptions.OpenAdrTransportException;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.Oadr20bJAXBContext;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPayload;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.utility.Oadr20bPayloadIds;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import javax.xml.namespace.QName;

@Slf4j
@Service
@RequiredArgsConstructor
public class VtnTransportService {

    private final RestClient restClient;
    private final OpenAdrProperties properties;
    private final RetryHandler retryHandler;
    private final ObjectProvider<RegistrationService> registrationServiceProvider;

    public Object send(String endpoint, Object payload) {
        return send(endpoint, payload, allowsEmptyResponse(payload));
    }

    /**
     * Sends an OpenADR response/acknowledgement for which the peer may return
     * HTTP 2xx without another OpenADR payload.
     */
    public void sendWithoutResponse(String endpoint, Object payload) {
        send(endpoint, payload, true);
    }

    private Object send(String endpoint, Object payload, boolean allowEmptyResponse) {
        try {
            Oadr20bJAXBContext jaxb = properties.getXml().isValidate()
                    ? Oadr20bJAXBContext.getInstance(properties.getXml().getXsdFolderPath())
                    : Oadr20bJAXBContext.getInstance();

            OadrPayload oadrPayload = Oadr20bFactory.createOadrPayload("oadrSignedObject", payload);
            JAXBElement<OadrPayload> jaxbElement = new JAXBElement<>(
                    new QName("http://openadr.org/oadr-2.0b/2012/07", "oadrPayload"),
                    OadrPayload.class,
                    oadrPayload
            );
            String xmlPayload = jaxb.marshal(jaxbElement, false);

            log.debug("Sending OpenADR payload to endpoint={}", endpoint);

            String xmlResponse = retryHandler.executeWithRetry(endpoint, () -> httpPost(endpoint, xmlPayload));

            log.debug("Received OpenADR response from endpoint={}", endpoint);

            if (xmlResponse == null || xmlResponse.isBlank()) {
                if (allowEmptyResponse) {
                    log.debug(
                            "VTN returned successful HTTP response without an OpenADR payload. endpoint={}",
                            endpoint
                    );
                    return null;
                }

                throw new OpenAdrTransportException(
                        "VTN returned empty response body for endpoint=" + endpoint);
            }

            Object rawResponse = jaxb.unmarshal(xmlResponse, properties.getXml().isValidate());
            Object response = unwrapIfNeeded(rawResponse);

            validateIds(response);
            checkApplicationLayerError(response);

            return response;
        } catch (JAXBException e) {
            throw new OpenAdrTransportException("Failed to initialize JAXB context", e);
        } catch (Oadr20bMarshalException e) {
            throw new OpenAdrTransportException("Failed to marshal OpenADR payload", e);
        } catch (Oadr20bUnmarshalException e) {
            throw new OpenAdrTransportException("Failed to unmarshal VTN response", e);
        }
    }

    private boolean allowsEmptyResponse(Object payload) {
        /*
         * These payloads are protocol responses/acknowledgements. When the VEN
         * posts one after receiving a VTN message through oadrPoll, the VTN may
         * complete the exchange with HTTP 2xx and an empty response body.
         */
        return payload instanceof OadrResponseType
                || payload instanceof OadrCreatedEventType
                || payload instanceof OadrRegisteredReportType
                || payload instanceof OadrCreatedReportType
                || payload instanceof OadrUpdatedReportType
                || payload instanceof OadrCanceledReportType
                || payload instanceof OadrCreatedOptType
                || payload instanceof OadrCanceledOptType
                || payload instanceof OadrCreatedPartyRegistrationType
                || payload instanceof OadrCanceledPartyRegistrationType;
    }

    private String httpPost(String endpoint, String xmlPayload) {
        try {
            return restClient.post()
                    .uri(buildUrl(endpoint))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE)
                    .body(xmlPayload)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw new OpenAdrHttpException(
                    "HTTP client error " + e.getStatusCode(), e.getStatusCode().value(), e);
        } catch (HttpServerErrorException e) {
            throw new OpenAdrHttpException(
                    "HTTP server error " + e.getStatusCode(), e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new OpenAdrHttpException("HTTP connection error", e);
        }
    }

    public Object queryRegistration(OadrQueryRegistrationType payload) {
        return send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, payload);
    }

    public OadrCreatedPartyRegistrationType register(OadrCreatePartyRegistrationType payload) {
        return cast(send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, payload), OadrCreatedPartyRegistrationType.class);
    }

    public Object poll(OadrPollType payload) {
        return send(Oadr20bUrlPath.OADR_POLL_SERVICE, payload);
    }

    public Object requestEvent(OadrRequestEventType payload) {
        return send(Oadr20bUrlPath.EI_EVENT_SERVICE, payload);
    }

    public OadrResponseType createdEvent(OadrCreatedEventType payload) {
        return castIfPresent(send(Oadr20bUrlPath.EI_EVENT_SERVICE, payload), OadrResponseType.class);
    }

    public OadrRegisteredReportType registerReport(OadrRegisterReportType payload) {
        return cast(send(Oadr20bUrlPath.EI_REPORT_SERVICE, payload), OadrRegisteredReportType.class);
    }

    public OadrCreatedReportType createdReport(OadrCreatedReportType payload) {
        return castIfPresent(send(Oadr20bUrlPath.EI_REPORT_SERVICE, payload), OadrCreatedReportType.class);
    }

    public OadrUpdatedReportType updateReport(OadrUpdateReportType payload) {
        return castIfPresent(send(Oadr20bUrlPath.EI_REPORT_SERVICE, payload), OadrUpdatedReportType.class);
    }

    public OadrCanceledReportType canceledReport(OadrCanceledReportType payload) {
        return castIfPresent(send(Oadr20bUrlPath.EI_REPORT_SERVICE, payload), OadrCanceledReportType.class);
    }

    public OadrCreatedOptType createOpt(OadrCreateOptType payload) {
        return cast(send(Oadr20bUrlPath.EI_OPT_SERVICE, payload), OadrCreatedOptType.class);
    }

    public OadrCanceledOptType cancelOpt(OadrCancelOptType payload) {
        return cast(send(Oadr20bUrlPath.EI_OPT_SERVICE, payload), OadrCanceledOptType.class);
    }

    public OadrCanceledPartyRegistrationType cancelPartyRegistration(OadrCancelPartyRegistrationType payload) {
        return cast(send(Oadr20bUrlPath.EI_REGISTER_PARTY_SERVICE, payload), OadrCanceledPartyRegistrationType.class);
    }

    private Object unwrapIfNeeded(Object response) {
        if (response instanceof OadrPayload payload) {
            Object unwrapped = Oadr20bFactory.getSignedObjectFromOadrPayload(payload);
            return unwrapped != null ? unwrapped : payload;
        }

        return response;
    }

    private void checkApplicationLayerError(Object response) {
        EiResponseType eiResponse = extractEiResponse(response);
        if (eiResponse == null
                || !String.valueOf(ApplicationLayerErrorCodes.NOT_REGISTERED)
                .equals(eiResponse.getResponseCode())) {
            return;
        }

        log.error("VTN returned 463 Not Registered/Authorized. " +
                "Certificate CN may not match venID. " +
                "Check that venID in config matches CN in VEN certificate.");
        throw new OpenAdrApplicationException(
                "VTN rejected request: 463 Not Registered/Authorized",
                ApplicationLayerErrorCodes.NOT_REGISTERED,
                eiResponse.getResponseDescription(),
                eiResponse.getRequestID()
        );
    }

    private EiResponseType extractEiResponse(Object response) {
        if (response == null) {
            return null;
        }

        return switch (response) {
            case OadrCreatedPartyRegistrationType r -> r.getEiResponse();
            case OadrRegisteredReportType r -> r.getEiResponse();
            case OadrResponseType r -> r.getEiResponse();

            case OadrCreatedReportType r -> r.getEiResponse();
            case OadrUpdatedReportType r -> r.getEiResponse();
            case OadrCanceledReportType r -> r.getEiResponse();

            case OadrCreatedOptType r -> r.getEiResponse();
            case OadrCanceledOptType r -> r.getEiResponse();

            case OadrCanceledPartyRegistrationType r -> r.getEiResponse();

            default -> null;
        };
    }

    private String buildUrl(String endpoint) {
        String baseUrl = properties.getVtn().getUrl();

        if (baseUrl.endsWith(Oadr20bUrlPath.OADR_BASE_PATH)) {
            return baseUrl + endpoint;
        }

        return baseUrl + Oadr20bUrlPath.OADR_BASE_PATH + endpoint;
    }

    private void validateIds(Object response) {
        if (response == null) {
            return;
        }

        // oadrCreatedPartyRegistration IS the source of truth for venID — validating it
        // against our own expectation is circular and would always fail when the VTN assigns
        // a different venID than requested.
        if (response instanceof OadrCreatedPartyRegistrationType) {
            return;
        }

        String expectedVenId = registrationServiceProvider.getObject().currentVenId();
        String expectedVtnId = properties.getVtn().getId();

        String receivedVenId = Oadr20bPayloadIds.venIdOf(response);
        String receivedVtnId = Oadr20bPayloadIds.vtnIdOf(response);

        if (receivedVenId != null && !receivedVenId.isBlank() && !expectedVenId.equals(receivedVenId)) {
            throw new OpenAdrTransportException(
                    "venID mismatch: expected=%s, received=%s".formatted(expectedVenId, receivedVenId)
            );
        }

        if (expectedVtnId != null && !expectedVtnId.isBlank()
                && receivedVtnId != null && !receivedVtnId.isBlank()
                && !expectedVtnId.equals(receivedVtnId)) {
            throw new OpenAdrTransportException(
                    "vtnID mismatch: expected=%s, received=%s".formatted(expectedVtnId, receivedVtnId)
            );
        }
    }

    private <T> T cast(Object response, Class<T> expectedType) {
        if (!expectedType.isInstance(response)) {
            throw new OpenAdrTransportException(
                    "Unexpected OpenADR response type. Expected=%s, actual=%s"
                            .formatted(expectedType.getSimpleName(), response == null ? "null" : response.getClass().getName())
            );
        }

        return expectedType.cast(response);
    }

    private <T> T castIfPresent(Object response, Class<T> expectedType) {
        return response == null ? null : cast(response, expectedType);
    }
}
