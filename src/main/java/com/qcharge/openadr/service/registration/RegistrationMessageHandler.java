package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.LogMessage.COMPLETED_CANCEL_PARTY_REGISTRATION;

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

    public RemoteCancellationDecision handleCancelPartyRegistration(
            OadrCancelPartyRegistrationType request, OpenAdrSessionSnapshot session
    ) {
        RemoteCancellationDecision decision =
                registrationService.prepareRemoteCancellation(request, session);

        if (decision != RemoteCancellationDecision.ACCEPTED) {
            return decision;
        }

        lifecycleCoordinator.acceptRemoteCancellation(session);
        registrationService.acknowledgeRemoteCancellation(request, session);
        log.info(COMPLETED_CANCEL_PARTY_REGISTRATION, request.getRegistrationID());
        return RemoteCancellationDecision.ACCEPTED;
    }
}
