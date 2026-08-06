package com.qcharge.openadr.controller;

import com.qcharge.openadr.service.event.ManualRequestEventService;
import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TestControllerRequestEventTest {

    @Mock RegistrationService registrationService;
    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;
    @Mock ManualRequestEventService manualRequestEventService;

    @Test
    void returnsAcceptedWithRequestId() {
        TestController controller = new TestController(
                registrationService,
                lifecycleCoordinator,
                manualRequestEventService
        );
        when(manualRequestEventService.requestEvents()).thenReturn("REQUEST-1");

        ResponseEntity<TestController.RequestEventAcceptedResponse> response =
                controller.requestEvent();

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("REQUEST-1", response.getBody().requestId());
        assertEquals("ACCEPTED", response.getBody().status());
    }
}
