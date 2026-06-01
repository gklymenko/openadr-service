package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.Oadr20bJAXBContext;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
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
import com.qcharge.openadr.exceptions.OpenAdrTransportException;
import com.qcharge.openadr.utility.Oadr20bPayloadIds;
import jakarta.xml.bind.JAXBException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class VtnTransportService {

    private final RestClient restClient;
    private final OpenAdrProperties properties;
    private final RetryHandler retryHandler;

    public Object send(String endpoint, Object payload) {
        try {
            Oadr20bJAXBContext jaxb = Oadr20bJAXBContext.getInstance();
            String xmlPayload = jaxb.marshalRoot(payload, properties.getXml().isValidate());

            log.debug("Sending OpenADR payload to endpoint={}", endpoint);

            String xmlResponse = retryHandler.executeWithRetry(endpoint,
                    () -> httpPost(endpoint, xmlPayload));

            log.debug("Received OpenADR response from endpoint={}", endpoint);

            Object rawResponse = jaxb.unmarshal(xmlResponse, properties.getXml().isValidate());
            Object response = unwrapIfNeeded(rawResponse);

            validateIds(response);

            return response;
        } catch (JAXBException e) {
            throw new OpenAdrTransportException("Failed to initialize JAXB context", e);
        } catch (Oadr20bMarshalException e) {
            throw new OpenAdrTransportException("Failed to marshal OpenADR payload", e);
        } catch (Oadr20bUnmarshalException e) {
            throw new OpenAdrTransportException("Failed to unmarshal VTN response", e);
        }
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
            throw new OpenAdrTransportException(
                    "HTTP client error " + e.getStatusCode(), e.getStatusCode().value(), e);
        } catch (HttpServerErrorException e) {
            throw new OpenAdrTransportException(
                    "HTTP server error " + e.getStatusCode(), e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new OpenAdrTransportException("HTTP connection error", e);
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
        return cast(send(Oadr20bUrlPath.EI_EVENT_SERVICE, payload), OadrResponseType.class);
    }

    public OadrRegisteredReportType registerReport(OadrRegisterReportType payload) {
        return cast(send(Oadr20bUrlPath.EI_REPORT_SERVICE, payload), OadrRegisteredReportType.class);
    }

    public OadrCreatedReportType createdReport(OadrCreatedReportType payload) {
        return cast(send(Oadr20bUrlPath.EI_REPORT_SERVICE, payload), OadrCreatedReportType.class);
    }

    public OadrUpdatedReportType updateReport(OadrUpdateReportType payload) {
        return cast(send(Oadr20bUrlPath.EI_REPORT_SERVICE, payload), OadrUpdatedReportType.class);
    }

    public OadrCanceledReportType canceledReport(OadrCanceledReportType payload) {
        return cast(send(Oadr20bUrlPath.EI_REPORT_SERVICE, payload), OadrCanceledReportType.class);
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

        String expectedVenId = properties.getVen().getId();
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
}