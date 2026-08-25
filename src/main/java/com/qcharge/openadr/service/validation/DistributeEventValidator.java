package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.hasText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireEiResponse;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireMatchingId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateRequestIdEcho;

/** Validates the oadrDistributeEvent exchange envelope, not its individual event entries. */
@Component
public class DistributeEventValidator implements OpenAdrExchangeValidator {

    @Override
    public boolean supports(OpenAdrExchangeContext<?, ?> context) {
        if (context.operation() == OpenAdrOperations.REQUEST_EVENT
                || context.operation() == OpenAdrOperations.CREATED_EVENT) {
            return true;
        }

        return context.operation() == OpenAdrOperations.POLL
                && (context.response() instanceof OadrDistributeEventType
                || context.response() instanceof OadrResponseType);
    }

    @Override
    public void validate(OpenAdrExchangeContext<?, ?> context) {
        if (context.response() instanceof OadrDistributeEventType response) {
            validateDistributeEvent(context, response);
            return;
        }

        if (context.request() instanceof OadrPollType
                && context.response() instanceof OadrResponseType response) {
            requireEiResponse(response.getEiResponse(), "oadrResponse", null);
            return;
        }

        if (context.request() instanceof OadrRequestEventType request
                && context.response() instanceof OadrResponseType response) {
            String requestId = requestIdOf(request);
            EiResponseType eiResponse = requireEiResponse(
                    response.getEiResponse(), "oadrResponse", requestId
            );
            validateRequestIdEcho(requestId, eiResponse, "oadrResponse");
            return;
        }

        if (context.request() instanceof OadrCreatedEventType request
                && context.response() instanceof OadrResponseType response) {
            String requestId = requestIdOf(request);
            EiResponseType eiResponse = requireEiResponse(
                    response.getEiResponse(), "oadrResponse", requestId
            );
            validateRequestIdEcho(requestId, eiResponse, "oadrResponse");
        }
    }

    private void validateDistributeEvent(
            OpenAdrExchangeContext<?, ?> context,
            OadrDistributeEventType response
    ) {
        String requestId = response.getRequestID();
        String expectedVtnId = context.session().vtnId();
        Object request = context.request();

        String originatingRequestId = request instanceof OadrRequestEventType requestEvent
                ? requestIdOf(requestEvent)
                : null;

        // Rules 41-42: event correlation uses the top-level requestID.
        requireText(requestId, "oadrDistributeEvent.requestID", originatingRequestId);

        requireText(response.getVtnID(), "oadrDistributeEvent.vtnID", requestId);
        if (hasText(expectedVtnId)) {
            requireMatchingId(
                    "oadrDistributeEvent.vtnID",
                    expectedVtnId,
                    response.getVtnID(),
                    requestId
            );
        }

        if (request instanceof OadrRequestEventType) {
            // Rule 40: eiResponse is required for a reply to oadrRequestEvent.
            requireEiResponse(response.getEiResponse(), "oadrDistributeEvent", originatingRequestId);
        }

        if (request instanceof OadrPollType) {
            requireEiResponse(response.getEiResponse(), "oadrDistributeEvent", null);
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
