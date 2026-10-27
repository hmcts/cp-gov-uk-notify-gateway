package uk.gov.hmcts.cp.notification.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uk.gov.hmcts.cp.notification.sender.NotificationStatus;
import uk.gov.hmcts.cp.taskmanager.domain.ExecutionInfo;
import uk.gov.hmcts.cp.taskmanager.service.task.ExecutableTask;
import uk.gov.hmcts.cp.taskmanager.service.task.Task;

import java.util.List;
import java.util.Optional;

/**
 * Polls a letter Gov.Notify has accepted until it has received it (legacy {@code check-accepted-letter-status}).
 * Gov.Notify can take days to print and post, so this polls far less often and for far longer: by default
 * every 12 hours, 16 times.
 */
@Task(CheckAcceptedLetterStatusTask.TASK_NAME)
@Component
public class CheckAcceptedLetterStatusTask implements ExecutableTask {
    public static final String TASK_NAME = "CHECK_ACCEPTED_LETTER_STATUS";

    private final LetterStatusPoller poller;
    private final List<Long> retryDurationsSecs;

    public CheckAcceptedLetterStatusTask(
            final LetterStatusPoller poller,
            @Value("${cp.notification.retry.letter-accepted-status-durations-secs}") final List<Long> retryDurationsSecs) {
        this.poller = poller;
        this.retryDurationsSecs = List.copyOf(retryDurationsSecs);
    }

    @Override
    public ExecutionInfo execute(final ExecutionInfo executionInfo) {
        return poller.poll(executionInfo, retryDurationsSecs, (letter, reference, current) ->
                poller.pollAgain(letter, NotificationStatus.ACCEPTED, current, retryDurationsSecs));
    }

    @Override
    public Optional<List<Long>> getRetryDurationsInSecs() {
        return Optional.of(retryDurationsSecs);
    }
}
