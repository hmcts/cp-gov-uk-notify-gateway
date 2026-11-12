package uk.gov.hmcts.cp.notification.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.logging.Dependency.BLOB_STORAGE;
import static uk.gov.hmcts.cp.notification.logging.Dependency.GOV_UK_NOTIFY;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MATERIAL;
import static uk.gov.hmcts.cp.notification.logging.Dependency.MICROSOFT_365;
import static uk.gov.hmcts.cp.notification.logging.Operation.DOWNLOAD;
import static uk.gov.hmcts.cp.notification.logging.Operation.SEND;

import uk.gov.hmcts.cp.notification.logging.Operation;

class RetryCauseTest {

    @Test
    void gov_notify_send_should_be_a_send_to_gov_uk_notify() {
        assertThat(RetryCause.GOV_NOTIFY_SEND.getDependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(RetryCause.GOV_NOTIFY_SEND.getOperation()).isEqualTo(SEND);
    }

    @Test
    void office365_send_should_be_a_send_to_microsoft_365() {
        assertThat(RetryCause.OFFICE365_SEND.getDependency()).isEqualTo(MICROSOFT_365);
        assertThat(RetryCause.OFFICE365_SEND.getOperation()).isEqualTo(SEND);
    }

    @Test
    void attachment_download_should_be_a_download_from_blob_storage() {
        assertThat(RetryCause.ATTACHMENT_DOWNLOAD.getDependency()).isEqualTo(BLOB_STORAGE);
        assertThat(RetryCause.ATTACHMENT_DOWNLOAD.getOperation()).isEqualTo(DOWNLOAD);
    }

    @Test
    void material_download_should_be_a_download_from_material() {
        assertThat(RetryCause.MATERIAL_DOWNLOAD.getDependency()).isEqualTo(MATERIAL);
        assertThat(RetryCause.MATERIAL_DOWNLOAD.getOperation()).isEqualTo(DOWNLOAD);
    }

    @Test
    void status_check_should_be_a_status_check_at_gov_uk_notify() {
        assertThat(RetryCause.STATUS_CHECK.getDependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(RetryCause.STATUS_CHECK.getOperation()).isEqualTo(Operation.STATUS_CHECK);
    }

    @Test
    void still_in_progress_should_be_a_status_check_at_gov_uk_notify() {
        assertThat(RetryCause.STILL_IN_PROGRESS.getDependency()).isEqualTo(GOV_UK_NOTIFY);
        assertThat(RetryCause.STILL_IN_PROGRESS.getOperation()).isEqualTo(Operation.STATUS_CHECK);
    }
}
