package com.qcharge.openadr.service.transport;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.ApiMessage.VTN_REJECT_OPERATION;

/**
 * Evaluates the envelope-level {@code eiResponse} returned by a VTN.
 *
 * <p>A non-200 {@code eiResponse} is an OpenADR application failure even
 * though the transport exchange itself completed with HTTP 200.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAdrApplicationResponseEvaluator {

    private final OpenAdrApplicationErrorPolicy errorPolicy;

    public void evaluate(OpenAdrOperation<?, ?> operation, Object response) {
        EiResponseType eiResponse = extractEiResponse(response);
        if (eiResponse == null) {
            return;
        }

        int responseCode = parseResponseCode(operation, eiResponse);
        if (responseCode == OpenADRResponseCode.OK) {
            return;
        }

        ApplicationErrorAction action = errorPolicy.classify(operation, responseCode);

        log.warn(
                "VTN returned OpenADR application error. operation={}, "
                        + "responseCode={}, requestId={}, action={}, description={}",
                operation.name(),
                responseCode, eiResponse.getRequestID(),
                action, eiResponse.getResponseDescription()
        );

        throw new OpenAdrApplicationException(
                VTN_REJECT_OPERATION.format(operation.name(), responseCode),
                responseCode, eiResponse.getResponseDescription(),
                eiResponse.getRequestID(), operation.name(), action
        );
    }

    private int parseResponseCode(
            OpenAdrOperation<?, ?> operation, EiResponseType eiResponse
    ) {
        String value = eiResponse.getResponseCode();

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new OpenAdrApplicationException(
                    "VTN returned invalid OpenADR responseCode=%s for operation=%s"
                            .formatted(value, operation.name()),
                    OpenADRResponseCode.COMPLIANCE_ERROR_OTHER,
                    "Missing or non-numeric OpenADR responseCode",
                    eiResponse.getRequestID(),
                    operation.name(),
                    ApplicationErrorAction.FAIL_OPERATION
            );
        }
    }

    private EiResponseType extractEiResponse(Object response) {
        if (response == null) {
            return null;
        }

        return switch (response) {
            case OadrCreatedPartyRegistrationType value -> value.getEiResponse();
            case OadrRegisteredReportType value -> value.getEiResponse();
            case OadrResponseType value -> value.getEiResponse();
            case OadrCreatedReportType value -> value.getEiResponse();
            case OadrUpdatedReportType value -> value.getEiResponse();
            case OadrCanceledReportType value -> value.getEiResponse();
            case OadrCreatedOptType value -> value.getEiResponse();
            case OadrCanceledOptType value -> value.getEiResponse();
            case OadrCanceledPartyRegistrationType value -> value.getEiResponse();
            case OadrDistributeEventType value -> value.getEiResponse();
            default -> null;
        };
    }
}
