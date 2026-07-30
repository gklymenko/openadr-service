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
import com.qcharge.openadr.service.validation.OpenAdrExchangeValidationService;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.xml.namespace.QName;

@Slf4j
@Service
@RequiredArgsConstructor
public class VtnTransportService {

    private final RestClient restClient;
    private final OpenAdrProperties properties;
    private final RetryHandler retryHandler;
    private final OpenAdrHttpStatusPolicy httpStatusPolicy;
    private final OpenAdrExchangeValidationService exchangeValidationService;
    private final OpenAdrApplicationResponseEvaluator applicationResponseEvaluator;
    private final OpenAdrApplicationErrorMapper applicationErrorMapper;
    private final OpenAdrReplyFactory replyFactory;

    public <Q, R> R send(OpenAdrOperation<Q, R> operation, Q payload) {
        operation.requireValidRequest(payload);

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

            log.debug(
                    "Sending OpenADR payload. operation={}, endpoint={}",
                    operation.name(),
                    operation.endpoint()
            );

            String xmlResponse = retryHandler.executeWithRetry(
                    operation.name(),
                    () -> httpPost(operation.endpoint(), xmlPayload)
            );

            log.debug(
                    "Received OpenADR response. operation={}, endpoint={}",
                    operation.name(),
                    operation.endpoint()
            );

            if (xmlResponse == null || xmlResponse.isBlank()) {
                if (operation.allowsEmptyResponse()) {
                    log.debug(
                            "VTN returned HTTP 200 without an OpenADR payload. operation={}, endpoint={}",
                            operation.name(),
                            operation.endpoint()
                    );
                    return null;
                }

                throw new OpenAdrTransportException(
                        "VTN returned empty response body for operation=%s, endpoint=%s"
                                .formatted(operation.name(), operation.endpoint())
                );
            }

            Object rawResponse = jaxb.unmarshal(xmlResponse, properties.getXml().isValidate());
            Object response = unwrapIfNeeded(rawResponse);

            // Application errors are valid OpenADR payloads and must not be
            // hidden by success-response type validation.
            applicationResponseEvaluator.evaluate(operation, response);

            R typedResponse = requireExpectedResponse(operation, response);

            OpenAdrExchangeContext<Q, R> context =
                    new OpenAdrExchangeContext<>(operation, payload, typedResponse);

            validateExchange(context);

            return typedResponse;
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
            ResponseEntity<String> response = restClient.post()
                    .uri(buildUrl(endpoint))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE)
                    .body(xmlPayload)
                    .retrieve()
                    .toEntity(String.class);

            int httpStatusCode = response.getStatusCode().value();
            if (httpStatusPolicy.classify(httpStatusCode) != HttpStatusAction.ACCEPT) {
                throw new OpenAdrHttpException(
                        "Unsupported OpenADR HTTP status " + httpStatusCode
                                + "; OpenADR Simple HTTP requires 200 for a handled response",
                        httpStatusCode,
                        null
                );
            }

            return response.getBody();
        } catch (RestClientResponseException e) {
            throw new OpenAdrHttpException(
                    "OpenADR HTTP error " + e.getStatusCode(),
                    e.getStatusCode().value(),
                    e
            );
        } catch (ResourceAccessException e) {
            throw new OpenAdrHttpException("HTTP connection error", e);
        }
    }

    public Object queryRegistration(OadrQueryRegistrationType payload) {
        return send(OpenAdrOperations.QUERY_REGISTRATION, payload);
    }

    public OadrCreatedPartyRegistrationType register(OadrCreatePartyRegistrationType payload) {
        return send(OpenAdrOperations.CREATE_PARTY_REGISTRATION, payload);
    }

    public Object poll(OadrPollType payload) {
        return send(OpenAdrOperations.POLL, payload);
    }

    public Object requestEvent(OadrRequestEventType payload) {
        return send(OpenAdrOperations.REQUEST_EVENT, payload);
    }

    public OadrResponseType createdEvent(OadrCreatedEventType payload) {
        return send(OpenAdrOperations.CREATED_EVENT, payload);
    }

    public OadrRegisteredReportType registerReport(OadrRegisterReportType payload) {
        return send(OpenAdrOperations.REGISTER_REPORT, payload);
    }

    public OadrResponseType createdReport(OadrCreatedReportType payload) {
        return send(OpenAdrOperations.CREATED_REPORT_RESPONSE, payload);
    }

    public OadrUpdatedReportType updateReport(OadrUpdateReportType payload) {
        return send(OpenAdrOperations.UPDATE_REPORT, payload);
    }

    public OadrResponseType canceledReport(OadrCanceledReportType payload) {
        return send(OpenAdrOperations.CANCELED_REPORT_RESPONSE, payload);
    }

    public OadrCreatedOptType createOpt(OadrCreateOptType payload) {
        return send(OpenAdrOperations.CREATE_OPT, payload);
    }

    public OadrCanceledOptType cancelOpt(OadrCancelOptType payload) {
        return send(OpenAdrOperations.CANCEL_OPT, payload);
    }

    public OadrCanceledPartyRegistrationType cancelPartyRegistration(OadrCancelPartyRegistrationType payload) {
        return send(OpenAdrOperations.CANCEL_PARTY_REGISTRATION, payload);
    }

    public void sendReply(OpenAdrReply<?, ?> reply) {
        sendCapturedReply(reply);
    }

    private <Q, R> void validateExchange(OpenAdrExchangeContext<Q, R> context) {
        try {
            exchangeValidationService.validate(context);
        } catch (RuntimeException failure) {
            OpenAdrApplicationException applicationError =
                    applicationErrorMapper.map(failure, context.response());

            replyFactory.createApplicationErrorReply(
                            context.response(),
                            venIdForReply(context.request()),
                            applicationError
                    )
                    .ifPresent(reply -> sendErrorReply(reply, applicationError));

            throw applicationError;
        }
    }

    private void sendErrorReply(
            OpenAdrReply<?, ?> reply,
            OpenAdrApplicationException applicationError
    ) {
        try {
            sendReply(reply);
            log.info(
                    "Sent OpenADR application error reply. operation={}, responseCode={}, requestId={}",
                    reply.operation().name(),
                    applicationError.getResponseCode(),
                    applicationError.getRequestId()
            );
        } catch (RuntimeException replyFailure) {
            applicationError.addSuppressed(replyFailure);
            log.error(
                    "Failed to send OpenADR application error reply. operation={}, responseCode={}, requestId={}",
                    reply.operation().name(),
                    applicationError.getResponseCode(),
                    applicationError.getRequestId(),
                    replyFailure
            );
        }
    }

    private String venIdForReply(Object request) {
        if (request instanceof OadrPollType poll) {
            return poll.getVenID();
        }

        if (request instanceof OadrRequestEventType requestEvent
                && requestEvent.getEiRequestEvent() != null) {
            return requestEvent.getEiRequestEvent().getVenID();
        }

        return properties.getVen().getId();
    }

    private <Q, R> void sendCapturedReply(OpenAdrReply<Q, R> reply) {
        send(reply.operation(), reply.payload());
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

    @SuppressWarnings("unchecked")
    private <Q, R> R requireExpectedResponse(OpenAdrOperation<Q, R> operation, Object response) {
        if (!operation.acceptsResponse(response)) {
            throw new OpenAdrApplicationException(
                    "Unexpected OpenADR response type for operation=%s. Expected one of=%s, actual=%s"
                            .formatted(
                                    operation.name(),
                                    operation.responseTypes().stream()
                                            .map(Class::getSimpleName)
                                            .sorted()
                                            .toList(),
                                    response == null ? "null" : response.getClass().getName()
                            ),
                    ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER,
                    "Payload not of expected type for operation=" + operation.name(),
                    null
            );
        }

        return (R) response;
    }
}
