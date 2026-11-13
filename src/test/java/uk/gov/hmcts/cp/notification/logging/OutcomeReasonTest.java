package uk.gov.hmcts.cp.notification.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alert queries read these values, so they must not change without changing the alerts (PEG-3718, PEG-3719).
 */
class OutcomeReasonTest {

    @Test
    void should_keep_the_field_values_the_alert_queries_read() {
        assertThat(OutcomeReason.UNREADABLE_REQUEST.getFieldValue()).isEqualTo("unreadable-request");
        assertThat(OutcomeReason.INVALID_REQUEST.getFieldValue()).isEqualTo("invalid-request");
        assertThat(OutcomeReason.ATTACHMENT_NOT_FOUND.getFieldValue()).isEqualTo("attachment-not-found");
        assertThat(OutcomeReason.ATTACHMENT_ACCESS_REFUSED.getFieldValue()).isEqualTo("attachment-access-refused");
        assertThat(OutcomeReason.ATTACHMENT_TOO_LARGE.getFieldValue()).isEqualTo("attachment-too-large");
        assertThat(OutcomeReason.ATTACHMENT_BAD_LINK.getFieldValue()).isEqualTo("attachment-bad-link");
        assertThat(OutcomeReason.ATTACHMENT_UNAVAILABLE.getFieldValue()).isEqualTo("attachment-unavailable");
        assertThat(OutcomeReason.PROVIDER_REJECTED.getFieldValue()).isEqualTo("provider-rejected");
        assertThat(OutcomeReason.PROVIDER_UNREACHABLE.getFieldValue()).isEqualTo("provider-unreachable");
        assertThat(OutcomeReason.PROVIDER_NO_RESPONSE.getFieldValue()).isEqualTo("provider-no-response");
        assertThat(OutcomeReason.PROVIDER_FAILED.getFieldValue()).isEqualTo("provider-failed");
        assertThat(OutcomeReason.DELIVERY_FAILED.getFieldValue()).isEqualTo("delivery-failed");
        assertThat(OutcomeReason.STATUS_UNKNOWN.getFieldValue()).isEqualTo("status-unknown");
        assertThat(OutcomeReason.values()).hasSize(13);
    }

    @Test
    void should_describe_each_reason_in_words() {
        assertThat(OutcomeReason.UNREADABLE_REQUEST.getDescription()).isEqualTo("the request could not be read");
        assertThat(OutcomeReason.INVALID_REQUEST.getDescription()).isEqualTo("the request failed validation");
        assertThat(OutcomeReason.ATTACHMENT_NOT_FOUND.getDescription()).isEqualTo("the attachment was not found");
        assertThat(OutcomeReason.ATTACHMENT_ACCESS_REFUSED.getDescription())
                .isEqualTo("access to the attachment was refused");
        assertThat(OutcomeReason.ATTACHMENT_TOO_LARGE.getDescription()).isEqualTo("the attachment is too large");
        assertThat(OutcomeReason.ATTACHMENT_BAD_LINK.getDescription())
                .isEqualTo("the attachment link is not one the gateway can use");
        assertThat(OutcomeReason.ATTACHMENT_UNAVAILABLE.getDescription())
                .isEqualTo("the attachment could not be downloaded within the retry window");
        assertThat(OutcomeReason.PROVIDER_REJECTED.getDescription()).isEqualTo("the provider rejected the notification");
        assertThat(OutcomeReason.PROVIDER_UNREACHABLE.getDescription())
                .isEqualTo("the provider could not be reached within the retry window");
        assertThat(OutcomeReason.PROVIDER_NO_RESPONSE.getDescription())
                .isEqualTo("the provider did not respond within the retry window");
        assertThat(OutcomeReason.PROVIDER_FAILED.getDescription())
                .isEqualTo("the provider kept failing until the retry window ended");
        assertThat(OutcomeReason.DELIVERY_FAILED.getDescription())
                .isEqualTo("the provider reported that the notification could not be delivered");
        assertThat(OutcomeReason.STATUS_UNKNOWN.getDescription())
                .isEqualTo("the provider had not finished with the notification when the retry window ended");
    }
}
