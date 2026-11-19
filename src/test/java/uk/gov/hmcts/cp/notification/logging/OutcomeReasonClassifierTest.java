package uk.gov.hmcts.cp.notification.logging;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.FailureReason.UNREACHABLE;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;
import static uk.gov.hmcts.cp.notification.logging.Operation.STATUS_CHECK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_ACCESS_REFUSED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_BAD_LINK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_TOO_LARGE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_UNAVAILABLE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_FAILED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_NO_RESPONSE;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_REJECTED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.PROVIDER_UNREACHABLE;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutcomeReasonClassifierTest {
    private final OutcomeReasonClassifier outcomeReasonClassifier = new OutcomeReasonClassifier();

    @Nested
    class AttachmentFailed {

        @Test
        void should_classify_a_404_as_attachment_not_found() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(of(404));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_NOT_FOUND);
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403})
        void should_classify_a_401_or_403_as_attachment_access_refused(final int statusCode) {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(of(statusCode));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_ACCESS_REFUSED);
        }

        @Test
        void should_classify_a_413_as_attachment_too_large() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(of(413));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_TOO_LARGE);
        }

        @Test
        void should_classify_a_400_as_attachment_bad_link() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(of(400));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_BAD_LINK);
        }

        @Test
        void should_classify_a_failure_with_no_status_code_as_attachment_bad_link() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.attachmentFailed(empty());

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_BAD_LINK);
        }
    }

    @Nested
    class ProviderFailed {

        @Test
        void should_be_provider_unreachable_when_the_provider_could_not_be_reached() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.providerFailed(of(UNREACHABLE));

            assertThat(outcomeReason).isEqualTo(PROVIDER_UNREACHABLE);
        }

        @Test
        void should_be_provider_no_response_when_the_provider_did_not_respond() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.providerFailed(of(NO_RESPONSE));

            assertThat(outcomeReason).isEqualTo(PROVIDER_NO_RESPONSE);
        }

        @Test
        void should_be_provider_rejected_when_there_is_no_reason() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.providerFailed(empty());

            assertThat(outcomeReason).isEqualTo(PROVIDER_REJECTED);
        }
    }

    @Nested
    class RetriesExhausted {

        @Test
        void should_be_attachment_unavailable_for_a_download_whatever_the_reason() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.retriesExhausted(DOWNLOAD, of(NO_RESPONSE));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_UNAVAILABLE);
        }

        @Test
        void should_be_provider_unreachable_when_the_provider_could_not_be_reached() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.retriesExhausted(SEND, of(UNREACHABLE));

            assertThat(outcomeReason).isEqualTo(PROVIDER_UNREACHABLE);
        }

        @Test
        void should_be_provider_no_response_when_the_provider_did_not_respond() {
            final OutcomeReason outcomeReason =
                    outcomeReasonClassifier.retriesExhausted(STATUS_CHECK, of(NO_RESPONSE));

            assertThat(outcomeReason).isEqualTo(PROVIDER_NO_RESPONSE);
        }

        @Test
        void should_be_provider_failed_when_there_is_no_reason() {
            final OutcomeReason outcomeReason = outcomeReasonClassifier.retriesExhausted(SEND, empty());

            assertThat(outcomeReason).isEqualTo(PROVIDER_FAILED);
        }
    }
}
