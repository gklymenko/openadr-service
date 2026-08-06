package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.model.oadr20b.ei.EiResponseType;
import com.qcharge.openadr.model.oadr20b.ei.SpecifierPayloadType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisterReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportRequestType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.hasText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.invalidId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.isSuccess;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.require;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireEiResponse;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.requireText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateOptionalId;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateRequestIdEcho;

@Component
public class ReportValidator implements OpenAdrExchangeValidator {

    private static final String METADATA = "METADATA";

    @Override
    public boolean supports(OpenAdrExchangeContext<?, ?> context) {
        return context.operation() == OpenAdrOperations.REGISTER_REPORT
                || context.operation() == OpenAdrOperations.UPDATE_REPORT
                || context.operation() == OpenAdrOperations.REGISTERED_REPORT_RESPONSE
                || context.operation() == OpenAdrOperations.CREATED_REPORT_RESPONSE
                || context.operation() == OpenAdrOperations.UPDATED_REPORT_RESPONSE
                || context.operation() == OpenAdrOperations.CANCELED_REPORT_RESPONSE;
    }

    @Override
    public void validate(OpenAdrExchangeContext<?, ?> context) {
        String requestId = requestIdOf(context.request());
        EiResponseType eiResponse = requireEiResponse(
                responseOf(context.response()),
                context.response().getClass().getSimpleName(),
                requestId
        );

        if (requiresRequestIdEcho(context)) {
            validateRequestIdEcho(
                    requestId,
                    eiResponse,
                    context.response().getClass().getSimpleName()
            );
        }

        validateResponseVenId(context, requestId);

        if (!isSuccess(eiResponse)) {
            return;
        }

        if (context.request() instanceof OadrRegisterReportType request
                && context.response() instanceof OadrRegisteredReportType response) {
            validateRegisteredReport(request, response);
        }

        if (context.request() instanceof OadrUpdateReportType request
                && context.response() instanceof OadrUpdatedReportType response) {
            validateUpdatedReport(request, response);
        }
    }

    private void validateRegisteredReport(
            OadrRegisterReportType request,
            OadrRegisteredReportType response
    ) {
        Map<String, Set<String>> offeredReports = offeredReports(request);
        Set<String> reportRequestIds = new HashSet<>();

        for (OadrReportRequestType reportRequest : response.getOadrReportRequest()) {
            String reportRequestId = requireText(
                    reportRequest == null ? null : reportRequest.getReportRequestID(),
                    "oadrRegisteredReport.oadrReportRequest.reportRequestID",
                    request.getRequestID()
            );

            if (!reportRequestIds.add(reportRequestId)) {
                throw invalidId(
                        "oadrRegisteredReport.oadrReportRequest.reportRequestID",
                        "unique value",
                        reportRequestId,
                        request.getRequestID()
                );
            }

            require(
                    reportRequest.getReportSpecifier() != null,
                    "oadrRegisteredReport.oadrReportRequest.reportSpecifier",
                    request.getRequestID()
            );

            String reportSpecifierId = requireText(
                    reportRequest.getReportSpecifier().getReportSpecifierID(),
                    "oadrRegisteredReport.oadrReportRequest.reportSpecifierID",
                    request.getRequestID()
            );

            if (METADATA.equalsIgnoreCase(reportSpecifierId)) {
                continue;
            }

            Set<String> offeredRids = offeredReports.get(reportSpecifierId);
            if (offeredRids == null) {
                throw invalidId(
                        "oadrRegisteredReport.oadrReportRequest.reportSpecifierID",
                        offeredReports.keySet().toString(),
                        reportSpecifierId,
                        request.getRequestID()
                );
            }

            for (SpecifierPayloadType payload :
                    reportRequest.getReportSpecifier().getSpecifierPayload()) {
                String rid = requireText(
                        payload == null ? null : payload.getRID(),
                        "oadrRegisteredReport.oadrReportRequest.rID",
                        request.getRequestID()
                );

                if (!offeredRids.contains(rid)) {
                    throw invalidId(
                            "oadrRegisteredReport.oadrReportRequest.rID",
                            offeredRids.toString(),
                            rid,
                            request.getRequestID()
                    );
                }
            }
        }
    }

    private void validateUpdatedReport(
            OadrUpdateReportType request,
            OadrUpdatedReportType response
    ) {
        if (response.getOadrCancelReport() == null) {
            return;
        }

        Set<String> deliveredReportRequestIds = request.getOadrReport().stream()
                .map(OadrReportType::getReportRequestID)
                .filter(OpenAdrValidationSupport::hasText)
                .collect(Collectors.toSet());

        for (String canceledId : response.getOadrCancelReport().getReportRequestID()) {
            if (!deliveredReportRequestIds.contains(canceledId)) {
                throw invalidId(
                        "oadrUpdatedReport.oadrCancelReport.reportRequestID",
                        deliveredReportRequestIds.toString(),
                        canceledId,
                        request.getRequestID()
                );
            }
        }
    }

    private Map<String, Set<String>> offeredReports(OadrRegisterReportType request) {
        Map<String, Set<String>> result = new HashMap<>();

        for (OadrReportType report : request.getOadrReport()) {
            if (report == null || !hasText(report.getReportSpecifierID())) {
                continue;
            }

            Set<String> rids = report.getOadrReportDescription().stream()
                    .map(description -> description == null ? null : description.getRID())
                    .filter(OpenAdrValidationSupport::hasText)
                    .collect(Collectors.toSet());

            result.put(report.getReportSpecifierID(), rids);
        }

        return result;
    }

    private void validateResponseVenId(
            OpenAdrExchangeContext<?, ?> context,
            String requestId
    ) {
        Object response = context.response();
        String actualVenId = switch (response) {
            case OadrRegisteredReportType value -> value.getVenID();
            case OadrUpdatedReportType value -> value.getVenID();
            case OadrResponseType value -> value.getVenID();
            default -> null;
        };

        if (!hasText(actualVenId)) {
            return;
        }

        validateOptionalId(
                response.getClass().getSimpleName() + ".venID",
                context.session().venId(),
                actualVenId,
                requestId
        );
    }

    private boolean requiresRequestIdEcho(OpenAdrExchangeContext<?, ?> context) {
        return context.operation() == OpenAdrOperations.REGISTER_REPORT
                || context.operation() == OpenAdrOperations.UPDATE_REPORT;
    }

    private String requestIdOf(Object request) {
        return switch (request) {
            case OadrRegisterReportType value -> value.getRequestID();
            case OadrUpdateReportType value -> value.getRequestID();
            case OadrRegisteredReportType value -> requestIdOf(value.getEiResponse());
            case OadrCreatedReportType value -> requestIdOf(value.getEiResponse());
            case OadrUpdatedReportType value -> requestIdOf(value.getEiResponse());
            case OadrCanceledReportType value -> requestIdOf(value.getEiResponse());
            default -> null;
        };
    }

    private String requestIdOf(EiResponseType eiResponse) {
        return eiResponse == null ? null : eiResponse.getRequestID();
    }

    private EiResponseType responseOf(Object response) {
        return switch (response) {
            case OadrRegisteredReportType value -> value.getEiResponse();
            case OadrUpdatedReportType value -> value.getEiResponse();
            case OadrResponseType value -> value.getEiResponse();
            default -> null;
        };
    }
}
