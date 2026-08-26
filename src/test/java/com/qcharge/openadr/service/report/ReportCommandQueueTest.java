package com.qcharge.openadr.service.report;

import com.qcharge.openadr.model.oadr20b.oadr.OadrCancelReportType;
import com.qcharge.openadr.model.oadr20b.oadr.OadrCreateReportType;
import com.qcharge.openadr.service.session.OpenAdrSessionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReportCommandQueueTest {

    @Mock ReportCommandProcessor processor;

    @Test
    void processesCommandsAsynchronouslyInPullOrder() {
        AtomicReference<Runnable> submittedTask = new AtomicReference<>();
        TaskExecutor executor = submittedTask::set;
        ReportCommandQueue queue = new ReportCommandQueue(processor, executor);
        OpenAdrSessionSnapshot session = session();
        PulledReportCommand first = PulledReportCommand.create(new OadrCreateReportType(), session);
        PulledReportCommand second = PulledReportCommand.cancel(new OadrCancelReportType(), session);

        queue.enqueueAll(List.of(first, second));

        verify(processor, never()).process(first);
        assertThat(submittedTask.get()).isNotNull();

        submittedTask.get().run();

        InOrder inOrder = inOrder(processor);
        inOrder.verify(processor).process(first);
        inOrder.verify(processor).process(second);
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
