package uk.gov.hmcts.cp.notification.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.persistence.NotificationRepository;
import uk.gov.hmcts.cp.notification.task.CpTaskFactory;
import uk.gov.hmcts.cp.notification.task.SendEmailTask;
import uk.gov.hmcts.cp.notification.time.Clock;
import uk.gov.hmcts.cp.taskmanager.service.ExecutionService;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class NotificationIngestionService {
    private static final String TYPE_EMAIL = "EMAIL";
    private static final String STATUS_QUEUED = "QUEUED";

    private static final Logger LOG = LoggerFactory.getLogger(NotificationIngestionService.class);

    private final NotificationRepository notificationRepository;
    private final ExecutionService executionService;
    private final CpTaskFactory taskFactory;
    private final Clock clock;

    @Transactional
    public void ingest(final SendEmailCommand command, final String replyTo) {
        final OffsetDateTime now = clock.offsetDateTime();

        // Only the delivery that wins the insert enqueues the send, so concurrent duplicates can't double-send.
        final int inserted = notificationRepository.insertIfAbsent(
                command.notificationId(), TYPE_EMAIL, STATUS_QUEUED, command.sendToAddress(),
                command.clientContext(), replyTo, now, now);
        if (inserted == 0) {
            LOG.info("Duplicate notificationId {} — ignoring (row already exists, no task)", command.notificationId());
            return;
        }

        executionService.executeWith(taskFactory.createSendEmailJob(command));
        LOG.info("Queued notification {} and enqueued {} task", command.notificationId(), SendEmailTask.TASK_NAME);
    }
}
