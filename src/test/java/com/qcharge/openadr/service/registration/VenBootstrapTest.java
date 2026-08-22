package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VenBootstrapTest {

    @Mock RegistrationService registrationService;
    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    private OpenAdrProperties properties;

    @BeforeEach
    void setUp() {
        properties = new OpenAdrProperties();
        properties.getVen().setId("VEN");
    }

    @Test
    void queriesCapabilitiesBeforeBootstrapWhenConfigured() {
        properties.getVen().setQueryRegistrationOnStartup(true);
        VenBootstrap bootstrap = new VenBootstrap(
                properties,
                registrationService,
                lifecycleCoordinator
        );

        bootstrap.onApplicationReady();

        InOrder calls = inOrder(registrationService, lifecycleCoordinator);
        calls.verify(registrationService).queryRegistration();
        calls.verify(lifecycleCoordinator).bootstrap();
    }

    @Test
    void bootstrapsDirectlyWhenCapabilityQueryIsDisabled() {
        properties.getVen().setQueryRegistrationOnStartup(false);
        VenBootstrap bootstrap = new VenBootstrap(
                properties,
                registrationService,
                lifecycleCoordinator
        );

        bootstrap.onApplicationReady();

        verify(registrationService, never()).queryRegistration();
        verify(lifecycleCoordinator).bootstrap();
    }
}