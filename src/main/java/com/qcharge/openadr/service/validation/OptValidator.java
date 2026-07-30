package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateOptType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedOptType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.isSuccess;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireEiResponse;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireMatchingId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateOptionalId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateRequestIdEcho;

@Component
public class OptValidator implements OpenAdrExchangeValidator {

    @Override
    public boolean supports(OpenAdrExchangeContext<?, ?> context) {
        return context.operation() == OpenAdrOperations.CREATE_OPT
                || context.operation() == OpenAdrOperations.CANCEL_OPT;
    }

    @Override
    public void validate(OpenAdrExchangeContext<?, ?> context) {
        switch (context.request()) {
            case OadrCreateOptType request ->
                    validateCreate(request, (OadrCreatedOptType) context.response());
            case OadrCancelOptType request ->
                    validateCancel(request, (OadrCanceledOptType) context.response());
            default -> throw new IllegalArgumentException(
                    "Unsupported opt request type: " + context.request().getClass().getName()
            );
        }
    }

    private void validateCreate(OadrCreateOptType request, OadrCreatedOptType response) {
        EiResponseType eiResponse = requireEiResponse(
                response.getEiResponse(),
                "oadrCreatedOpt",
                request.getRequestID()
        );
        validateRequestIdEcho(request.getRequestID(), eiResponse, "oadrCreatedOpt");

        if (isSuccess(eiResponse)) {
            requireMatchingId(
                    "oadrCreatedOpt.optID",
                    request.getOptID(),
                    response.getOptID(),
                    request.getRequestID()
            );
        }
    }

    private void validateCancel(OadrCancelOptType request, OadrCanceledOptType response) {
        EiResponseType eiResponse = requireEiResponse(
                response.getEiResponse(),
                "oadrCanceledOpt",
                request.getRequestID()
        );
        validateRequestIdEcho(request.getRequestID(), eiResponse, "oadrCanceledOpt");

        if (isSuccess(eiResponse)) {
            validateOptionalId(
                    "oadrCanceledOpt.optID",
                    request.getOptID(),
                    response.getOptID(),
                    request.getRequestID()
            );
        }
    }
}
