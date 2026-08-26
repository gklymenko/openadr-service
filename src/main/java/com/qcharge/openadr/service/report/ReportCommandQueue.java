package com.qcharge.openadr.service.report;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ReportCommandQueue {

    private final ReportCommandProcessor processor;
    private final TaskExecutor executor;
    private final Queue<PulledReportCommand> commands = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    public ReportCommandQueue(
            ReportCommandProcessor processor,
            @Qualifier("reportCommandExecutor") TaskExecutor executor
    ) {
        this.processor = processor;
        this.executor = executor;
    }

    public void enqueueAll(Collection<PulledReportCommand> newCommands) {
        if (newCommands.isEmpty()) {
            return;
        }

        commands.addAll(newCommands);
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }

        try {
            executor.execute(this::drain);
        } catch (RuntimeException schedulingFailure) {
            draining.set(false);
            throw schedulingFailure;
        }
    }

    private void drain() {
        try {
            PulledReportCommand command;

            while ((command = commands.poll()) != null) {
                processor.process(command);
            }
        } finally {
            draining.set(false);

            if (!commands.isEmpty()) {
                scheduleDrain();
            }
        }
    }
}
