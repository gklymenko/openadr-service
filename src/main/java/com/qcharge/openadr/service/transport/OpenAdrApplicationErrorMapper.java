package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.exceptions.OutOfSequenceEventException;
import com.qcharge.openadr.exceptions.TargetMismatchException;
import com.qcharge.openadr.exceptions.UnsupportedOpenAdrSignalException;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatePartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrQueryRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.service.event.EventValidationException;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.COMPLIANCE_ERROR_OTHER;
import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.INVALID_DATA;
import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.OUT_OF_SEQUENCE;
import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.SIGNAL_NOT_SUPPORTED;
import static com.qcharge.openadr.exceptions.ApplicationLayerErrorCodes.TARGET_MISMATCH;

/**
 * Converts domain/validation failures into OpenADR application-layer errors.
 * HTTP and transport failures must not be passed to this mapper.
 */
@Component
public class OpenAdrApplicationErrorMapper {

    public OpenAdrApplicationException map(RuntimeException failure, Object requestPayload) {
        String requestId = requestIdOf(requestPayload);

        if (failure instanceof OpenAdrApplicationException applicationFailure) {
            if (!hasText(requestId)
                    || requestId.equals(applicationFailure.getRequestId())) {
                return applicationFailure;
            }

            return error(
                    applicationFailure.getMessage(),
                    applicationFailure.getResponseCode(),
                    applicationFailure.getResponseDescription(),
                    requestId,
                    applicationFailure
            );
        }

        if (failure instanceof EventValidationException eventFailure) {
            return error(
                    eventFailure.getMessage(),
                    eventFailure.getResponseCode(),
                    eventFailure.getMessage(),
                    requestId,
                    eventFailure
            );
        }

        if (failure instanceof OutOfSequenceEventException) {
            return error(
                    failure.getMessage(),
                    OUT_OF_SEQUENCE,
                    "Event modification number is out of sequence",
                    requestId,
                    failure
            );
        }

        if (failure instanceof TargetMismatchException) {
            return error(
                    failure.getMessage(),
                    TARGET_MISMATCH,
                    "OpenADR target does not match this VEN",
                    requestId,
                    failure
            );
        }

        if (failure instanceof UnsupportedOpenAdrSignalException) {
            return error(
                    failure.getMessage(),
                    SIGNAL_NOT_SUPPORTED,
                    "OpenADR signal is not supported",
                    requestId,
                    failure
            );
        }

        if (failure instanceof IllegalArgumentException) {
            return error(
                    failure.getMessage(),
                    INVALID_DATA,
                    "OpenADR payload contains invalid data",
                    requestId,
                    failure
            );
        }

        return error(
                failure.getMessage(),
                COMPLIANCE_ERROR_OTHER,
                "OpenADR request could not be processed",
                requestId,
                failure
        );
    }

    private OpenAdrApplicationException error(
            String message,
            int responseCode,
            String description,
            String requestId,
            RuntimeException cause
    ) {
        OpenAdrApplicationException mapped = new OpenAdrApplicationException(
                message == null ? description : message,
                responseCode,
                description,
                normalize(requestId)
        );
        mapped.initCause(cause);
        return mapped;
    }

    private String requestIdOf(Object payload) {
        return switch (payload) {
            case OadrDistributeEventType value -> value.getRequestID();
            case OadrCreateReportType value -> value.getRequestID();
            case OadrRegisterReportType value -> value.getRequestID();
            case OadrCancelReportType value -> value.getRequestID();
            case OadrUpdateReportType value -> value.getRequestID();
            case OadrCreateOptType value -> value.getRequestID();
            case OadrCancelOptType value -> value.getRequestID();
            case OadrCreatePartyRegistrationType value -> value.getRequestID();
            case OadrQueryRegistrationType value -> value.getRequestID();
            case OadrCancelPartyRegistrationType value -> value.getRequestID();
            default -> null;
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
