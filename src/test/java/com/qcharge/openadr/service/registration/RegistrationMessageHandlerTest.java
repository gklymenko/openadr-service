package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelPartyRegistrationType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrRequestReregistrationType;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.qcharge.openadr.TestSessionFixtures.registeredSession;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationMessageHandlerTest {

    @Mock RegistrationService registrationService;
    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    @InjectMocks RegistrationMessageHandler handler;

    @Test
    void requestReregistrationAcknowledgesBeforeStartingLifecycleFlow() {
        OadrRequestReregistrationType request =
                new OadrRequestReregistrationType();
        OpenAdrSessionSnapshot session = registeredSession();

        handler.handleRequestReregistration(request, session);

        var order = org.mockito.Mockito.inOrder(
                registrationService,
                lifecycleCoordinator
        );
        order.verify(registrationService)
                .acknowledgeRequestReregistration(request, session);
        order.verify(lifecycleCoordinator).reregister(session);
    }

    @Test
    void validRemoteCancellationAdvancesLifecycle() {
        OadrCancelPartyRegistrationType request =
                new OadrCancelPartyRegistrationType();
        OpenAdrSessionSnapshot session = registeredSession();
        when(registrationService.acknowledgeCancelPartyRegistration(request, session))
                .thenReturn(true);

        handler.handleCancelPartyRegistration(request, session);

        verify(lifecycleCoordinator).acceptRemoteCancellation(session);
    }

    @Test
    void invalidRemoteCancellationDoesNotChangeLifecycle() {
        OadrCancelPartyRegistrationType request =
                new OadrCancelPartyRegistrationType();
        OpenAdrSessionSnapshot session = registeredSession();
        when(registrationService.acknowledgeCancelPartyRegistration(request, session))
                .thenReturn(false);

        handler.handleCancelPartyRegistration(request, session);

        verify(lifecycleCoordinator, never()).acceptRemoteCancellation(session);
    }
}
