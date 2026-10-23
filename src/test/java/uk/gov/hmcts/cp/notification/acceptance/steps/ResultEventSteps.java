package uk.gov.hmcts.cp.notification.acceptance.steps;

import io.cucumber.java.en.Then;
import org.springframework.beans.factory.annotation.Autowired;

import static uk.gov.hmcts.cp.notification.integration.stubs.ResultQueueStubService.aResultQueue;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ContractSchema.NOTIFICATION_FAILED;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ContractSchema.NOTIFICATION_SENT;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport.RESULT_QUEUE;

public class ResultEventSteps {

    @Autowired
    private ScenarioContext context;

    @Then("a notification-sent result event is published to the originator's reply queue")
    public void a_notification_sent_result_event_is_published_to_the_reply_queue() {
        aResultQueue(RESULT_QUEUE)
                .receivesResultEvent()
                .withSubject("public.notificationnotify.events.notification-sent")
                .withField("notificationId", context.getNotificationId())
                .withField("emailSubject", "Your NCES extract")
                .withField("emailBody", "Please find your report attached.")
                .withField("replyToAddress", "noreply@justice.gov.uk");
    }

    @Then("a notification-sent result event for the Office 365 email is published to the originator's reply queue")
    public void a_notification_sent_result_event_for_the_office365_email_is_published_to_the_reply_queue() {
        aResultQueue(RESULT_QUEUE)
                .receivesResultEvent()
                .withSubject("public.notificationnotify.events.notification-sent")
                .withField("notificationId", context.getNotificationId())
                .withField("emailSubject", "Your document is ready")
                .withField("emailBody", "Please find the document attached.")
                .withField("replyToAddress", "NOREPLY@noreply.com");
    }

    @Then("a notification-sent result event for the letter is published to the originator's reply queue")
    public void a_notification_sent_result_event_for_the_letter_is_published_to_the_reply_queue() {
        aResultQueue(RESULT_QUEUE)
                .receivesResultEvent()
                .withSubject("public.notificationnotify.events.notification-sent")
                .withField("notificationId", context.getNotificationId())
                .withField("clientContext", "correspondence")
                .withFieldPresent("sentTime")
                .withFieldPresent("completedAt")
                .withFieldAbsent("sendToAddress")
                .withFieldAbsent("emailSubject")
                .withFieldAbsent("emailBody")
                .withFieldAbsent("replyToAddress")
                .conformsTo(NOTIFICATION_SENT);
    }

    @Then("a notification-failed result event for the letter with status {int} is published to the originator's reply queue")
    public void a_notification_failed_result_event_for_the_letter_with_status_is_published_to_the_reply_queue(
            final int status) {
        aResultQueue(RESULT_QUEUE)
                .receivesResultEvent()
                .withSubject("public.notificationnotify.events.notification-failed")
                .withField("notificationId", context.getNotificationId())
                .withField("statusCode", status)
                .withField("clientContext", "correspondence")
                .conformsTo(NOTIFICATION_FAILED);
    }

    @Then("a notification-failed result event for the letter saying no automatic resends remain is published to the originator's reply queue")
    public void a_notification_failed_result_event_for_the_letter_saying_no_automatic_resends_remain_is_published() {
        aResultQueue(RESULT_QUEUE)
                .receivesResultEvent()
                .withSubject("public.notificationnotify.events.notification-failed")
                .withField("notificationId", context.getNotificationId())
                .withField("statusCode", 400)
                .withField("errorMessage", "Validation failed for '" + context.getNotificationId()
                        + "' automatic resend attempt, remaining = 0")
                .withField("clientContext", "correspondence")
                .conformsTo(NOTIFICATION_FAILED);
    }

    @Then("a notification-failed result event for the letter is published to the originator's reply queue")
    public void a_notification_failed_result_event_for_the_letter_is_published_to_the_reply_queue() {
        aResultQueue(RESULT_QUEUE)
                .receivesResultEvent()
                .withSubject("public.notificationnotify.events.notification-failed")
                .withField("notificationId", context.getNotificationId())
                .withField("clientContext", "correspondence")
                .conformsTo(NOTIFICATION_FAILED);
    }

    @Then("a notification-failed result event is published to the originator's reply queue")
    public void a_notification_failed_result_event_is_published_to_the_reply_queue() {
        aResultQueue(RESULT_QUEUE)
                .receivesResultEvent()
                .withSubject("public.notificationnotify.events.notification-failed")
                .withField("notificationId", context.getNotificationId());
    }

    @Then("a notification-failed result event with status {int} is published to the originator's reply queue")
    public void a_notification_failed_result_event_with_status_is_published_to_the_reply_queue(final int status) {
        aResultQueue(RESULT_QUEUE)
                .receivesResultEvent()
                .withSubject("public.notificationnotify.events.notification-failed")
                .withField("notificationId", context.getNotificationId())
                .withField("statusCode", status);
    }

    @Then("no result event is published")
    public void no_result_event_is_published() {
        aResultQueue(RESULT_QUEUE).receivesNoResultEvent();
    }
}
