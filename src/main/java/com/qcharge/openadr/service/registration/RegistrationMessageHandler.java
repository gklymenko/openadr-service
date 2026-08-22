package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Handles VTN-initiated registration commands received through polling. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistrationMessageHandler {

    private final RegistrationService registrationService;
    private final OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    public void handleRequestReregistration(
            OadrRequestReregistrationType request, OpenAdrSessionSnapshot session
    ) {
        registrationService.acknowledgeRequestReregistration(request, session);
        lifecycleCoordinator.reregister(session);
    }

    public void handleCancelPartyRegistration(
            OadrCancelPartyRegistrationType request, OpenAdrSessionSnapshot session
    ) {
        if (!registrationService.acknowledgeCancelPartyRegistration(request, session)) {
            return;
        }

        lifecycleCoordinator.acceptRemoteCancellation(session);
        log.info(
                "VTN-initiated registration cancellation completed. registrationId={}",
                request.getRegistrationID()
        );
    }
}
