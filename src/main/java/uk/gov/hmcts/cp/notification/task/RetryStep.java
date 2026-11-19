package uk.gov.hmcts.cp.notification.task;

/** Where a notification task's run stands in its retries, from {@link TaskRetrySchedule#nextStep}. */
public enum RetryStep {
    /** More than one retry attempt remains: retry. */
    RETRY,
    /** One retry attempt remains: reschedule this task as the final attempt. */
    FINAL_ATTEMPT,
    /** No retry attempts remain, or this run is the final attempt: give up. */
    EXHAUSTED
}
