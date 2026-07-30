package com.qcharge.openadr.controller;

import com.qcharge.openadr.service.registration.RegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints for manually triggering VEN-initiated OpenADR actions
 * during QualityLogic Test Harness certification testing.
 *
 * NEVER active outside local/test profiles.
 */
@Slf4j
@RestController
@Profile({"local", "test"})
@RequiredArgsConstructor
public class TestController {

    private final RegistrationService registrationService;

    @PostMapping("/test/cancel-registration")
    public ResponseEntity<String> cancelRegistration() {
        log.warn("TEST ENDPOINT: manually triggering VEN-initiated registration cancellation");
        registrationService.initiateCancelRegistration();
        return ResponseEntity.ok("Cancellation initiated");
    }

    @PostMapping("/test/force-new-registration")
    public ResponseEntity<String> forceNewRegistration() {
        log.warn("TEST ENDPOINT: manually triggering forced NEW registration (no registrationID)");
        registrationService.initiateForcedNewRegistration();
        return ResponseEntity.ok("New registration initiated");
    }

    @PostMapping("/test/reregister")
    public ResponseEntity<String> reregister() {
        log.warn("TEST ENDPOINT: manually triggering re-registration (with existing registrationID)");
        registrationService.register();
        return ResponseEntity.ok("Re-registration initiated");
    }

    @GetMapping("/test/query-registration")
    public ResponseEntity<String> queryRegistration() {
        log.warn("TEST ENDPOINT: manually triggering oadrQueryRegistration");
        registrationService.queryRegistration();
        return ResponseEntity.ok("QueryRegistration initiated");
    }
}