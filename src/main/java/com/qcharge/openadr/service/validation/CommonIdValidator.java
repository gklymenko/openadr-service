package com.qcharge.openadr.service.validation;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.model.entity.VenRegistration;
import com.qcharge.openadr.model.enums.VenRegistrationStatus;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCanceledReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrDistributeEventType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRegisteredReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrResponseType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdateReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrUpdatedReportType;
import com.qcharge.openadr.repository.VenRegistrationRepository;
import com.qcharge.openadr.service.transport.OpenAdrExchangeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.hasText;
import static com.qcharge.openadr.service.validation.OpenAdrValidationSupport.validateOptionalId;

/**
 * Rule 21 validation shared by all service-specific validators.
 */
@Component
@RequiredArgsConstructor
public class CommonIdValidator implements OpenAdrExchangeValidator {

    private final OpenAdrProperties properties;
    private final VenRegistrationRepository registrationRepository;

    @Override
    public boolean supports(OpenAdrExchangeContext<?, ?> context) {
        return context.response() != null
                && !(context.response() instanceof OadrCreatedPartyRegistrationType);
    }

    @Override
    public void validate(OpenAdrExchangeContext<?, ?> context) {
        Object response = context.response();
        String requestId = context.operation().name();

        String receivedVenId = venIdOf(response);
        String receivedVtnId = vtnIdOf(response);

        if (hasText(receivedVenId)) {
            validateOptionalId(
                    response.getClass().getSimpleName() + ".venID",
                    currentVenId(),
                    receivedVenId,
                    requestId
            );
        }

        if (hasText(receivedVtnId) && hasText(properties.getVtn().getId())) {
            validateOptionalId(
                    response.getClass().getSimpleName() + ".vtnID",
                    properties.getVtn().getId(),
                    receivedVtnId,
                    requestId
            );
        }
    }

    private String currentVenId() {
        return registrationRepository
                .findFirstByStatusOrderByUpdatedAtDesc(VenRegistrationStatus.REGISTERED)
                .map(VenRegistration::getVenId)
                .filter(OpenAdrValidationSupport::hasText)
                .orElse(properties.getVen().getId());
    }

    private String venIdOf(Object payload) {
        return switch (payload) {
            case OadrResponseType value -> value.getVenID();
            case OadrRegisteredReportType value -> value.getVenID();
            case OadrCreatedReportType value -> value.getVenID();
            case OadrUpdatedReportType value -> value.getVenID();
            case OadrCanceledReportType value -> value.getVenID();
            case OadrCanceledPartyRegistrationType value -> value.getVenID();
            case OadrCreateReportType value -> value.getVenID();
            case OadrCancelReportType value -> value.getVenID();
            case OadrUpdateReportType value -> value.getVenID();
            case OadrCancelPartyRegistrationType value -> value.getVenID();
            case OadrRequestReregistrationType value -> value.getVenID();
            default -> null;
        };
    }

    private String vtnIdOf(Object payload) {
        return payload instanceof OadrDistributeEventType value
                ? value.getVtnID()
                : null;
    }
}
