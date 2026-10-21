package uk.gov.hmcts.cp.notification.integration.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Component
public class JobTestRepository {

    private static final String COUNT_BY_TASK_SQL =
            "SELECT count(*) FROM jobs WHERE assigned_task_name = ?";
    private static final String RETRIES_REMAINING_BY_TASK_SQL =
            "SELECT retry_attempts_remaining FROM jobs WHERE assigned_task_name = ?";
    private static final String JOB_DATA_BY_TASK_SQL =
            "SELECT job_data FROM jobs WHERE assigned_task_name = ?";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JobTestRepository(final JdbcTemplate jdbcTemplate,
                             final PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public int countForTask(final String taskName) {
        final Integer count = jdbcTemplate.queryForObject(COUNT_BY_TASK_SQL, Integer.class, taskName);
        return count == null ? 0 : count;
    }

    public Optional<Integer> retryAttemptsRemainingForTask(final String taskName) {
        return jdbcTemplate.queryForList(RETRIES_REMAINING_BY_TASK_SQL, Integer.class, taskName).stream().findFirst();
    }

    public String jobDataForTask(final String taskName) {
        return jdbcTemplate.queryForObject(JOB_DATA_BY_TASK_SQL, String.class, taskName);
    }

    public void deleteAll() {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("DELETE FROM jobs"));
    }
}
