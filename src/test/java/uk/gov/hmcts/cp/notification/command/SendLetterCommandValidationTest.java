package uk.gov.hmcts.cp.notification.command;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.aSendLetterCommand;

class SendLetterCommandValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        factory.close();
    }

    @Test
    void accepts_a_letter_with_a_notification_id_and_a_letter_url() {
        assertThat(validator.validate(aSendLetterCommand().build())).isEmpty();
    }

    @Test
    void rejects_a_letter_without_a_notification_id() {
        final SendLetterCommand command = aSendLetterCommand().notificationId(null).build();

        assertThat(validator.validate(command))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("notificationId"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void rejects_a_letter_without_a_letter_url(final String letterUrl) {
        final SendLetterCommand command = aSendLetterCommand().letterUrl(letterUrl).build();

        assertThat(validator.validate(command))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("letterUrl"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "FIRST", "First"})
    void is_first_class_when_the_postage_is_first_in_any_case(final String postage) {
        assertThat(aSendLetterCommand().postage(postage).build().isFirstClass()).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "second", "economy", "first class"})
    void is_not_first_class_for_any_other_postage(final String postage) {
        assertThat(aSendLetterCommand().postage(postage).build().isFirstClass()).isFalse();
    }
}
