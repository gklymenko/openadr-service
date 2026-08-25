package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.model.enums.event.EventStatus;
import com.qcharge.openadr.model.oadr20b.ei.EiEventType;
import com.qcharge.openadr.model.oadr20b.ei.EiEventSignalType;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.ei.EventDescriptorType;
import com.qcharge.openadr.model.oadr20b.ei.IntervalType;
import com.qcharge.openadr.model.oadr20b.ei.PayloadFloatType;
import com.qcharge.openadr.model.oadr20b.ei.SignalPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrPollType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType.OadrEvent;
import com.qcharge.openadr.service.event.EventValidationException;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.utility.OpenAdrTimeUtils;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.hasText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireEiResponse;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireMatchingId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateRequestIdEcho;

@Component
public class EventValidator implements OpenAdrExchangeValidator {

    /** Validates one event inside a distributeEvent without rejecting valid siblings. */
    public void validateEvent(OadrEvent source) {
        if (source == null) {
            throw complianceError("oadrEvent is required");
        }

        EiEventType event = source.getEiEvent();
        if (event == null) {
            throw complianceError("eiEvent is required");
        }

        EventDescriptorType descriptor = event.getEventDescriptor();
        validateDescriptor(descriptor);
        validateActivePeriod(event);
        validateSignals(event);
    }

    private void validateDescriptor(EventDescriptorType descriptor) {
        if (descriptor == null) {
            throw complianceError("eventDescriptor is required");
        }

        if (!hasText(descriptor.getEventID())) {
            throw complianceError("eventID is required");
        }

        if (descriptor.getModificationNumber() < 0L) {
            throw complianceError("modificationNumber must not be negative");
        }

        if (descriptor.getEventStatus() == null) {
            throw invalidData("eventStatus is required");
        }

        String status = descriptor.getEventStatus().value();
        try {
            EventStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw invalidData("Unsupported eventStatus: " + status);
        }
    }

    private void validateActivePeriod(EiEventType event) {
        if (event.getEiActivePeriod() == null
                || event.getEiActivePeriod().getProperties() == null) {
            throw complianceError("eiActivePeriod is required");
        }

        var properties = event.getEiActivePeriod().getProperties();
        if (properties.getDtstart() == null
                || properties.getDtstart().getDateTime() == null) {
            throw complianceError("Event start time is required");
        }
        try {
            OpenAdrTimeUtils.fromXmlDateTime(properties.getDtstart().getDateTime());
        } catch (RuntimeException exception) {
            throw complianceError("Invalid event start time");
        }

        if (properties.getDuration() == null) {
            throw complianceError("Event duration is required");
        }
        validateDuration(properties.getDuration().getDuration(), "event");

        String startAfter = properties.getTolerance() != null
                && properties.getTolerance().getTolerate() != null
                ? properties.getTolerance().getTolerate().getStartafter()
                : null;
        if (startAfter != null
                && validateDuration(startAfter, "event startafter") < 0L) {
            throw invalidData("startafter must not be negative");
        }

        if (properties.getXEiRampUp() != null) {
            validateDuration(properties.getXEiRampUp().getDuration(), "event rampUp");
        }
        if (properties.getXEiRecovery() != null) {
            validateDuration(properties.getXEiRecovery().getDuration(), "event recovery");
        }
    }

    private void validateSignals(EiEventType event) {
        if (event.getEiEventSignals() == null
                || event.getEiEventSignals().getEiEventSignal().isEmpty()) {
            return;
        }

        for (EiEventSignalType signal : event.getEiEventSignals().getEiEventSignal()) {
            validateSignal(signal);
        }
    }

    private void validateSignal(EiEventSignalType signal) {
        if (signal == null) {
            throw complianceError("eiEventSignal is required");
        }
        if (signal.getIntervals() == null
                || signal.getIntervals().getInterval().isEmpty()) {
            throw complianceError(
                    "At least one interval is required for signal " + signal.getSignalID()
            );
        }

        for (IntervalType interval : signal.getIntervals().getInterval()) {
            validateInterval(signal.getSignalID(), interval);
        }
    }

    private void validateInterval(String signalId, IntervalType interval) {
        if (interval == null) {
            throw complianceError("Interval is required for signal " + signalId);
        }

        String uid = interval.getUid() != null ? interval.getUid().getText() : null;
        if (interval.getDuration() == null) {
            throw complianceError(
                    "Duration is required for interval for signal %s, uid=%s"
                            .formatted(signalId, uid)
            );
        }
        validateDuration(
                interval.getDuration().getDuration(),
                "interval for signal %s, uid=%s".formatted(signalId, uid)
        );

        var payloads = interval.getStreamPayloadBase();
        if (payloads.size() != 1
                || payloads.getFirst() == null
                || !(payloads.getFirst().getValue() instanceof SignalPayloadType signalPayload)) {
            throw complianceError(
                    "Exactly one signalPayload is required for signal %s, uid=%s"
                            .formatted(signalId, uid)
            );
        }
        if (signalPayload.getPayloadBase() == null
                || !(signalPayload.getPayloadBase().getValue() instanceof PayloadFloatType)) {
            throw complianceError(
                    "A numeric payloadFloat is required for signal %s, uid=%s"
                            .formatted(signalId, uid)
            );
        }
    }

    private long validateDuration(String value, String subject) {
        try {
            return OpenAdrTimeUtils.parseOpenAdrDuration(value)
                    .orElseThrow(() -> complianceError("Duration is required for " + subject))
                    .getSeconds();
        } catch (EventValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw complianceError("Invalid duration for %s: %s".formatted(subject, value));
        }
    }

    @Override
    public boolean supports(OpenAdrExchangeContext<?, ?> context) {
        if (context.operation() == OpenAdrOperations.REQUEST_EVENT
                || context.operation() == OpenAdrOperations.CREATED_EVENT) {
            return true;
        }

        return context.operation() == OpenAdrOperations.POLL
                && (
                context.response() instanceof OadrDistributeEventType
                        || context.response() instanceof OadrResponseType
        );
    }

    @Override
    public void validate(OpenAdrExchangeContext<?, ?> context) {
        if (context.response() instanceof OadrDistributeEventType response) {
            validateDistributeEvent(context, response);
            return;
        }

        if (context.request() instanceof OadrPollType
                && context.response() instanceof OadrResponseType response) {
            requireEiResponse(
                    response.getEiResponse(),
                    "oadrResponse",
                    null
            );
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
            OpenAdrExchangeContext<?, ?> context,
            OadrDistributeEventType response
    ) {
        String requestId = response.getRequestID();
        String expectedVtnId = context.session().vtnId();
        Object request = context.request();

        String originatingRequestId = request instanceof OadrRequestEventType requestEvent
                ? requestIdOf(requestEvent)
                : null;

        requireText(
                requestId,
                "oadrDistributeEvent.requestID",
                originatingRequestId
        );

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
            // Rule 40 requires eiResponse for a distributeEvent returned from
            // oadrRequestEvent. Its requestID may be empty; event correlation
            // uses the top-level oadrDistributeEvent.requestID (Rules 41-42).
            requireEiResponse(
                    response.getEiResponse(),
                    "oadrDistributeEvent",
                    originatingRequestId
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

    private EventValidationException complianceError(String message) {
        return new EventValidationException(
                message,
                OpenADRResponseCode.COMPLIANCE_ERROR_OTHER
        );
    }

    private EventValidationException invalidData(String message) {
        return new EventValidationException(message, OpenADRResponseCode.INVALID_DATA);
    }
}
