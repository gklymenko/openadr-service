package com.qcharge.openadr.service.report;

import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import com.qcharge.openadr.service.transport.OpenAdrReply;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import com.qcharge.openadr.service.transport.VtnTransportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportCommandProcessor {

    private final ReportRequestHandler reportRequestHandler;
    private final OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;
    private final OpenAdrApplicationErrorMapper applicationErrorMapper;
    private final OpenAdrReplyFactory replyFactory;
    private final VtnTransportService transportService;

    public void process(PulledReportCommand command) {
        if (!lifecycleCoordinator.isActive(command.session())) {
            log.info(
                    "Skipping queued report command from inactive OpenADR session. " +
                            "payloadType={}, generation={}",
                    command.payload().getClass().getSimpleName(),
                    command.session().generation()
            );
            return;
        }

        try {
            dispatch(command);
        } catch (RuntimeException failure) {
            sendApplicationError(command, failure);
        }
    }

    private void dispatch(PulledReportCommand command) {
        switch (command) {
            case PulledReportCommand.Create create ->
                    reportRequestHandler.handle(create.payload(), create.session());
            case PulledReportCommand.Register register ->
                    reportRequestHandler.handleRegisterReport(register.payload(), register.session());
            case PulledReportCommand.Cancel cancel ->
                    reportRequestHandler.handleCancelReport(cancel.payload(), cancel.session());
            case PulledReportCommand.Update update ->
                    reportRequestHandler.handleUpdateReport(update.payload(), update.session());
        }
    }

    private void sendApplicationError(
            PulledReportCommand command,
            RuntimeException failure
    ) {
        var applicationError = applicationErrorMapper.map(failure, command.payload());
        OpenAdrReply<?, ?> reply = replyFactory
                .createApplicationErrorReply(
                        command.payload(),
                        command.session().venId(),
                        applicationError
                )
                .orElseThrow(() -> applicationError);

        transportService.sendReply(reply, command.session());

        log.warn(
                "Queued report command failed and application error reply was sent. " +
                        "payloadType={}, responseCode={}, requestId={}",
                command.payload().getClass().getSimpleName(),
                applicationError.getResponseCode(),
                applicationError.getRequestId()
        );
    }
}
