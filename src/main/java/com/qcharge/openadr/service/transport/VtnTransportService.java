package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.ApiMessage;
import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.exceptions.OpenAdrHttpException;
import com.qcharge.openadr.exceptions.OpenAdrTransportException;
import com.qcharge.openadr.model.oadr20b.Oadr20bFactory;
import com.qcharge.openadr.model.oadr20b.Oadr20bJAXBContext;
import com.qcharge.openadr.model.oadr20b.Oadr20bUrlPath;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bMarshalException;
import com.qcharge.openadr.model.oadr20b.exception.Oadr20bUnmarshalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPayload;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.service.session.OpenAdrSessionProvider;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
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
    private final OpenAdrSessionProvider sessionProvider;

    public <Q, R> R send(OpenAdrOperation<Q, R> operation, Q payload) {
        return send(operation, payload, sessionProvider.current());
    }

    public <Q, R> R send(
            OpenAdrOperation<Q, R> operation, Q payload, OpenAdrSessionSnapshot session
    ) {
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

            logRawEventResponse(operation, xmlResponse);

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
                    new OpenAdrExchangeContext<>(operation, session, payload, typedResponse);

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

    private void logRawEventResponse(
            OpenAdrOperation<?, ?> operation,
            String xmlResponse
    ) {
        if (operation == OpenAdrOperations.POLL
                || operation == OpenAdrOperations.REQUEST_EVENT) {
            log.info(
                    "Raw VTN event response. operation={}, endpoint={}, xml={}",
                    operation.name(),
                    operation.endpoint(),
                    xmlResponse
            );
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


    public Object poll(OadrPollType payload) {
        return send(OpenAdrOperations.POLL, payload);
    }

    public void sendReply(
            OpenAdrReply<?, ?> reply,
            OpenAdrSessionSnapshot session
    ) {
        sendCapturedReply(reply, session);
    }

    private <Q, R> void validateExchange(OpenAdrExchangeContext<Q, R> context) {
        try {
            exchangeValidationService.validate(context);
        } catch (RuntimeException failure) {
            OpenAdrApplicationException applicationError =
                    applicationErrorMapper.map(failure, context.response());

            replyFactory.createApplicationErrorReply(
                            context.response(),
                            context.session().venId(),
                            applicationError
                    )
                    .ifPresent(reply -> sendErrorReply(
                            reply,
                            applicationError,
                            context.session()
                    ));

            throw applicationError;
        }
    }

    private void sendErrorReply(
            OpenAdrReply<?, ?> reply,
            OpenAdrApplicationException applicationError,
            OpenAdrSessionSnapshot session
    ) {
        try {
            sendReply(reply, session);
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

    private <Q, R> void sendCapturedReply(
            OpenAdrReply<Q, R> reply,
            OpenAdrSessionSnapshot session
    ) {
        send(reply.operation(), reply.payload(), session);
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

    private <Q, R> R requireExpectedResponse(OpenAdrOperation<Q, R> operation, Object response) {
        for (Class<? extends R> responseType : operation.responseTypes()) {
            if (responseType.isInstance(response)) {
                return responseType.cast(response);
            }
        }

        throw unexpectedResponseType(operation, response);
    }

    private OpenAdrApplicationException unexpectedResponseType(OpenAdrOperation<?, ?> operation, Object response) {
        String errorMsg = ApiMessage.UNEXPECTED_VTN_PAYLOAD_TYPE.format(operation.name(),
                operation.responseTypes().stream()
                        .map(Class::getSimpleName)
                        .sorted()
                        .toList(),
                response == null ? "null" : response.getClass().getName());


        String errorDescription = ApiMessage.UNEXPECTED_VTN_PAYLOAD_TYPE_DESCR.format(operation.name());

        return new OpenAdrApplicationException(
                errorMsg, OpenADRResponseCode.COMPLIANCE_ERROR_OTHER, errorDescription, null
        );
    }
}
