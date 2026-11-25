package uk.gov.hmcts.cp.notification.failure;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.failure.DownloadSource.BLOB_ATTACHMENT;
import static uk.gov.hmcts.cp.notification.failure.DownloadSource.MATERIAL_DOCUMENT_LINK;
import static uk.gov.hmcts.cp.notification.failure.DownloadSource.SAS_DOCUMENT;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_ACCESS_REFUSED;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_BAD_LINK;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_NOT_FOUND;
import static uk.gov.hmcts.cp.notification.logging.OutcomeReason.ATTACHMENT_TOO_LARGE;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import uk.gov.hmcts.cp.notification.logging.OutcomeReason;

class DownloadStatusTableTest {
    private final DownloadStatusTable downloadStatusTable = new DownloadStatusTable();

    @Nested
    class FailsAtOnce {

        @ParameterizedTest
        @ValueSource(ints = {400, 401, 403, 404, 413})
        void should_fail_a_material_document_link_request_at_once_when_material_refuses_it(final int statusCode) {
            final boolean failsAtOnce = downloadStatusTable.failsAtOnce(MATERIAL_DOCUMENT_LINK, statusCode);

            assertThat(failsAtOnce).isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {200, 408, 429, 500, 503})
        void should_retry_a_material_document_link_request_with_any_other_status(final int statusCode) {
            final boolean failsAtOnce = downloadStatusTable.failsAtOnce(MATERIAL_DOCUMENT_LINK, statusCode);

            assertThat(failsAtOnce).isFalse();
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403, 404})
        void should_fail_a_sas_document_download_at_once_when_access_is_refused_or_it_is_missing(final int statusCode) {
            final boolean failsAtOnce = downloadStatusTable.failsAtOnce(SAS_DOCUMENT, statusCode);

            assertThat(failsAtOnce).isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 413, 500, 503})
        void should_retry_a_sas_document_download_with_any_other_status(final int statusCode) {
            final boolean failsAtOnce = downloadStatusTable.failsAtOnce(SAS_DOCUMENT, statusCode);

            assertThat(failsAtOnce).isFalse();
        }

        @ParameterizedTest
        @ValueSource(ints = {403, 404})
        void should_fail_a_blob_attachment_download_at_once_when_forbidden_or_missing(final int statusCode) {
            final boolean failsAtOnce = downloadStatusTable.failsAtOnce(BLOB_ATTACHMENT, statusCode);

            assertThat(failsAtOnce).isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 401, 413, 500, 503})
        void should_retry_a_blob_attachment_download_with_any_other_status(final int statusCode) {
            final boolean failsAtOnce = downloadStatusTable.failsAtOnce(BLOB_ATTACHMENT, statusCode);

            assertThat(failsAtOnce).isFalse();
        }
    }

    @Nested
    class OutcomeReasonFor {

        @Test
        void should_be_attachment_not_found_for_a_404() {
            final OutcomeReason outcomeReason = downloadStatusTable.outcomeReasonFor(of(404));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_NOT_FOUND);
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403})
        void should_be_attachment_access_refused_for_a_401_or_403(final int statusCode) {
            final OutcomeReason outcomeReason = downloadStatusTable.outcomeReasonFor(of(statusCode));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_ACCESS_REFUSED);
        }

        @Test
        void should_be_attachment_too_large_for_a_413() {
            final OutcomeReason outcomeReason = downloadStatusTable.outcomeReasonFor(of(413));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_TOO_LARGE);
        }

        @Test
        void should_be_attachment_bad_link_for_any_other_status() {
            final OutcomeReason outcomeReason = downloadStatusTable.outcomeReasonFor(of(400));

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_BAD_LINK);
        }

        @Test
        void should_be_attachment_bad_link_without_a_status_code() {
            final OutcomeReason outcomeReason = downloadStatusTable.outcomeReasonFor(empty());

            assertThat(outcomeReason).isEqualTo(ATTACHMENT_BAD_LINK);
        }
    }
}
