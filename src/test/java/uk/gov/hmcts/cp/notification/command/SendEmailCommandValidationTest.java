package uk.gov.hmcts.cp.notification.command;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommandWithMaterialUrl;

class SendEmailCommandValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void rejects_a_malformed_reply_to_address() {
        final SendEmailCommand command = aSendEmailCommand().replyToAddress("not-an-email").build();

        assertThat(validator.validate(command))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("replyToAddress"));
    }

    @Test
    void accepts_a_well_formed_reply_to_address() {
        final SendEmailCommand command = aSendEmailCommand().replyToAddress("reply@example.com").build();

        assertThat(validator.validate(command)).isEmpty();
    }

    @Test
    void accepts_an_absent_reply_to_address() {
        final SendEmailCommand command = aSendEmailCommand().replyToAddress(null).build();

        assertThat(validator.validate(command)).isEmpty();
    }

    @Test
    void accepts_a_material_url() {
        final SendEmailCommand command = aSendEmailCommandWithMaterialUrl().build();

        assertThat(validator.validate(command)).isEmpty();
    }
}
