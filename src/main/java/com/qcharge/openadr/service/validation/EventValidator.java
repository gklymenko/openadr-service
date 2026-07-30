package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.hasText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireEiResponse;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireMatchingId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateRequestIdEcho;

@Component
@RequiredArgsConstructor
public class EventValidator implements OpenAdrExchangeValidator {

    private final OpenAdrProperties properties;

    @Override
    public boolean supports(OpenAdrExchangeContext<?, ?> context) {
        return context.operation() == OpenAdrOperations.REQUEST_EVENT
                || context.operation() == OpenAdrOperations.CREATED_EVENT
                || context.operation() == OpenAdrOperations.POLL
                && context.response() instanceof OadrDistributeEventType;
    }

    @Override
    public void validate(OpenAdrExchangeContext<?, ?> context) {
        if (context.response() instanceof OadrDistributeEventType response) {
            validateDistributeEvent(context.request(), response);
            return;
        }

        if (context.request() instanceof OadrRequestEventType request
                && context.response() instanceof OadrResponseType response) {
            String requestId = requestIdOf(request);
            EiResponseType eiResponse = requireEiResponse(
                    response.getEiResponse(),
                    "oadrResponse",
                    requestId
            );
            validateRequestIdEcho(requestId, eiResponse, "oadrResponse");
            return;
        }

        if (context.request() instanceof OadrCreatedEventType request
                && context.response() instanceof OadrResponseType response) {
            String requestId = requestIdOf(request);
            EiResponseType eiResponse = requireEiResponse(
                    response.getEiResponse(),
                    "oadrResponse",
                    requestId
            );
            validateRequestIdEcho(requestId, eiResponse, "oadrResponse");
        }
    }

    private void validateDistributeEvent(
            Object request,
            OadrDistributeEventType response
    ) {
        String requestId = response.getRequestID();
        String expectedVtnId = properties.getVtn().getId();

        requireText(response.getVtnID(), "oadrDistributeEvent.vtnID", requestId);
        if (hasText(expectedVtnId)) {
            requireMatchingId(
                    "oadrDistributeEvent.vtnID",
                    expectedVtnId,
                    response.getVtnID(),
                    requestId
            );
        }

        if (request instanceof OadrRequestEventType requestEvent) {
            String expectedRequestId = requestIdOf(requestEvent);
            EiResponseType eiResponse = requireEiResponse(
                    response.getEiResponse(),
                    "oadrDistributeEvent",
                    expectedRequestId
            );
            validateRequestIdEcho(
                    expectedRequestId,
                    eiResponse,
                    "oadrDistributeEvent"
            );
        }

        if (request instanceof OadrPollType) {
            requireEiResponse(
                    response.getEiResponse(),
                    "oadrDistributeEvent",
                    null
            );
        }
    }

    private String requestIdOf(OadrRequestEventType request) {
        return request.getEiRequestEvent() == null
                ? null
                : request.getEiRequestEvent().getRequestID();
    }

    private String requestIdOf(OadrCreatedEventType request) {
        if (request.getEiCreatedEvent() == null
                || request.getEiCreatedEvent().getEiResponse() == null) {
            return null;
        }

        return request.getEiCreatedEvent().getEiResponse().getRequestID();
    }
}
