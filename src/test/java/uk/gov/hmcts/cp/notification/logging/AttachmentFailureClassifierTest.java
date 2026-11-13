package uk.gov.hmcts.cp.notification.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_ACCESS_REFUSED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_BAD_LINK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_TOO_LARGE;

class AttachmentFailureClassifierTest {
    private final AttachmentFailureClassifier attachmentFailureClassifier = new AttachmentFailureClassifier();

    @Test
    void should_classify_a_404_as_attachment_not_found() {
        final OutcomeReason outcomeReason = attachmentFailureClassifier.classify(of(404));

        assertThat(outcomeReason).isEqualTo(ATTACHMENT_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void should_classify_a_401_or_403_as_attachment_access_refused(final int statusCode) {
        final OutcomeReason outcomeReason = attachmentFailureClassifier.classify(of(statusCode));

        assertThat(outcomeReason).isEqualTo(ATTACHMENT_ACCESS_REFUSED);
    }

    @Test
    void should_classify_a_413_as_attachment_too_large() {
        final OutcomeReason outcomeReason = attachmentFailureClassifier.classify(of(413));

        assertThat(outcomeReason).isEqualTo(ATTACHMENT_TOO_LARGE);
    }

    @Test
    void should_classify_a_400_as_attachment_bad_link() {
        final OutcomeReason outcomeReason = attachmentFailureClassifier.classify(of(400));

        assertThat(outcomeReason).isEqualTo(ATTACHMENT_BAD_LINK);
    }

    @Test
    void should_classify_a_failure_with_no_status_code_as_attachment_bad_link() {
        final OutcomeReason outcomeReason = attachmentFailureClassifier.classify(empty());

        assertThat(outcomeReason).isEqualTo(ATTACHMENT_BAD_LINK);
    }
}
