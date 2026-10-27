package uk.gov.hmcts.cp.notification.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;

/**
 * Polls a letter Gov.Notify has just been sent (legacy {@code check-letter-status}). Once Gov.Notify has
 * accepted it, the slower {@link CheckAcceptedLetterStatusTask} takes over, as in legacy.
 */
@Task(CheckLetterStatusTask.TASK_NAME)
@Component
public class CheckLetterStatusTask implements ExecutableTask {
    public static final String TASK_NAME = "CHECK_LETTER_STATUS";
    public static final String KEY_REFERENCE = "reference";

    private final LetterStatusPoller poller;
    private final ExecutionService executionService;
    private final CpTaskFactory taskFactory;
    private final List<Long> retryDurationsSecs;

    public CheckLetterStatusTask(
            final LetterStatusPoller poller,
            final ExecutionService executionService,
            final CpTaskFactory taskFactory,
            @Value("${cp.notification.retry.letter-status-durations-secs}") final List<Long> retryDurationsSecs) {
        this.poller = poller;
        this.executionService = executionService;
        this.taskFactory = taskFactory;
        this.retryDurationsSecs = List.copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        return poller.poll(executionInfo, retryDurationsSecs, (letter, reference, current) -> {
            executionService.executeWith(taskFactory.createCheckAcceptedLetterStatusJob(letter, reference));
            return TransientFailurePolicy.completed(current);
        });
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
