package com.qcharge.openadr.service.report;

import com.qcharge.openadr.exceptions.OpenADRResponseCode;
import com.qcharge.openadr.exceptions.OpenAdrApplicationException;
import com.qcharge.openadr.model.oadr20b.builders.Oadr20bEiReportBuilders;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreatedReportType;
import com.qcharge.openadr.service.session.OpenAdrSessionLifecycleCoordinator;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import com.qcharge.openadr.service.transport.OpenAdrApplicationErrorMapper;
import com.qcharge.openadr.service.transport.OpenAdrOperations;
import com.qcharge.openadr.service.transport.OpenAdrReply;
import com.qcharge.openadr.service.transport.OpenAdrReplyFactory;
import com.qcharge.openadr.service.transport.VtnTransportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportCommandProcessorBehaviorTest {

    @Mock ReportRequestHandler reportRequestHandler;
    @Mock OpenAdrSessionLifecycleCoordinator lifecycleCoordinator;
    @Mock VtnTransportService transportService;

    @Test
    void r1_3190_returns452CreatedReportForInvalidReportId() {
        OpenAdrSessionSnapshot session = session();
        var createReport = Oadr20bEiReportBuilders
                .newOadr20bCreateReportBuilder("CREATE-REQUEST", "VEN-1")
                .build();
        when(lifecycleCoordinator.isActive(session)).thenReturn(true);
        doThrow(new OpenAdrApplicationException(
                "Unknown reportSpecifierID",
                OpenADRResponseCode.INVALID_ID,
                "Unknown reportSpecifierID",
                "CREATE-REQUEST"
        )).when(reportRequestHandler).handle(createReport, session);
        ReportCommandProcessor processor = new ReportCommandProcessor(
                reportRequestHandler,
                lifecycleCoordinator,
                new OpenAdrApplicationErrorMapper(),
                new OpenAdrReplyFactory(),
                transportService
        );
        ArgumentCaptor<OpenAdrReply<?, ?>> reply = openAdrReplyCaptor();

        processor.process(PulledReportCommand.create(createReport, session));

        verify(transportService).sendReply(reply.capture(), eq(session));
        assertThat(reply.getValue().operation())
                .isEqualTo(OpenAdrOperations.CREATED_REPORT_RESPONSE);
        OadrCreatedReportType payload = (OadrCreatedReportType) reply.getValue().payload();
        assertThat(payload.getEiResponse().getResponseCode()).isEqualTo("452");
        assertThat(payload.getEiResponse().getRequestID()).isEqualTo("CREATE-REQUEST");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<OpenAdrReply<?, ?>> openAdrReplyCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(OpenAdrReply.class);
    }

    private OpenAdrSessionSnapshot session() {
        return new OpenAdrSessionSnapshot(
                1L,
                1L,
                "VEN-1",
                "VTN-1",
                "REGISTRATION-1",
                Duration.ofSeconds(10)
        );
    }
}
