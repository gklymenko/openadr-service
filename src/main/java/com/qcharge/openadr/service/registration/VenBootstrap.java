package com.qcharge.openadr.service.registration;

import com.qcharge.openadr.config.OpenAdrProperties;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import static com.qcharge.openadr.LogMessage.FAILED_VEN_BOOTSTRAP;
import static com.qcharge.openadr.LogMessage.START_VEN_BOOTSTRAP;

/** Starts the VEN lifecycle after the Spring application is ready. */
@Slf4j
@Component
@RequiredArgsConstructor
public class VenBootstrap {

    private final OpenAdrProperties properties;
    private final OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info(START_VEN_BOOTSTRAP, properties.getVen().getId());

        try {
            lifecycleCoordinator.bootstrap();
        } catch (Exception failure) {
            log.error(FAILED_VEN_BOOTSTRAP, failure);
        }
    }
}
