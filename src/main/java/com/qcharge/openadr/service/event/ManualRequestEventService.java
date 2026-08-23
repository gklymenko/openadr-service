package com.qcharge.openadr.service.event;

import com.qcharge.openadr.service.registration.RegistrationService;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.utility.RequestUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@Profile({"local", "test"})
@RequiredArgsConstructor
public class ManualRequestEventService {

    private final OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;
    private final EventPoller eventPoller;
    private final EventRequestService eventRequestService;
    private final TaskScheduler openAdrTaskScheduler;

    public String requestEvents() {
        OpenAdrSessionSnapshot session =
                lifecycleCoordinator.requireRegisteredSession();
        String requestId = RequestUtils.newRequestId();

        openAdrTaskScheduler.schedule(
                () -> executeRequest(session, requestId),
                Instant.now()
        );

        return requestId;
    }

    private void executeRequest(
            OpenAdrSessionSnapshot session,
            String requestId
    ) {
        try {
            eventPoller.executeExclusivelyWithPolling(() -> {
                if (!lifecycleCoordinator.isActive(session)) {
                    log.warn(
                            "Skipping manual oadrRequestEvent for inactive session. "
                                    + "requestId={}, generation={}",
                            requestId,
                            session.generation()
                    );
                    return;
                }

                eventRequestService.requestAllEvents(session, requestId);
            });
        } catch (RuntimeException failure) {
            log.error(
                    "Manual oadrRequestEvent failed. requestId={}, generation={}",
                    requestId,
                    session.generation(),
                    failure
            );
        }
    }
}
