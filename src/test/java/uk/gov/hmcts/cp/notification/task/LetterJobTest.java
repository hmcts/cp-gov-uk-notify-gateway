package uk.gov.hmcts.cp.notification.task;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import uk.gov.hmcts.cp.notification.time.Clock;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.LETTER_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.aFirstClassSendLetterCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.aSendLetterCommand;

class LetterJobTest {

    @Test
    void starts_a_first_class_letter_with_the_legacy_five_automatic_resends() {
        final UUID notificationId = UUID.randomUUID();

        final LetterJob letter = LetterJob.from(aFirstClassSendLetterCommand().notificationId(notificationId).build());

        assertThat(letter.notificationId()).isEqualTo(notificationId);
        assertThat(letter.letterUrl()).isEqualTo(LETTER_URL);
        assertThat(letter.firstClass()).isTrue();
        assertThat(letter.resendAttemptsRemaining()).isEqualTo(5);
    }

    @Test
    void starts_a_letter_with_any_other_postage_as_not_first_class() {
        assertThat(LetterJob.from(aSendLetterCommand().postage("second").build()).firstClass()).isFalse();
    }

    @Test
    void reads_back_the_job_data_it_was_written_to() {
        final LetterJob letter = new LetterJob(UUID.randomUUID(), LETTER_URL, true, 3);

        final LetterJob readBack = LetterJob.from(
                new CpTaskFactory(JsonMapper.builder().build(), new Clock()).createSendLetterJob(letter).getJobData());

        assertThat(readBack).isEqualTo(letter);
    }

    @Test
    void sends_again_with_one_fewer_resend() {
        final LetterJob letter = new LetterJob(UUID.randomUUID(), LETTER_URL, false, 5);

        assertThat(letter.canResend()).isTrue();
        assertThat(letter.forResend()).isEqualTo(new LetterJob(letter.notificationId(), LETTER_URL, false, 4));
    }

    @Test
    void cannot_resend_once_no_resends_are_left() {
        assertThat(new LetterJob(UUID.randomUUID(), LETTER_URL, false, 0).canResend()).isFalse();
    }
}
