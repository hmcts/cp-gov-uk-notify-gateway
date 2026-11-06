package uk.gov.hmcts.cp.notification.messaging;

import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import uk.gov.hmcts.cp.notification.command.SendLetterCommand;
import uk.gov.hmcts.cp.notification.integration.base.AbstractAsbConsumerSliceTest;
import uk.gov.hmcts.cp.notification.integration.stubs.ASBTestClient;
import uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.gov.hmcts.cp.notification.integration.Fixtures.load;
import static uk.gov.hmcts.cp.notification.integration.stubs.ASBTestClient.anAsbTestClient;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ContractSchema.SEND_LETTER_COMMAND;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ContractSchema.assertConformsTo;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ContractSchema.violations;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.LETTER_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.aFirstClassSendLetterCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendLetterCommandFactory.aSendLetterCommand;
import static uk.gov.hmcts.cp.notification.messaging.CommandMessageProcessor.MISSING_REPLY_TO;

class SendLetterConsumerIntegrationTest extends AbstractAsbConsumerSliceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String REPLY_TO = "ng-result-correspondence";

    private final ASBTestClient asb = anAsbTestClient(ServiceBusContainerSupport.SLICE_LETTER_COMMAND_QUEUE);

    @AfterEach
    void purgeTheLetterDeadLetterQueue() {
        asb.purgeDeadLetterQueue();
    }

    @Nested
    class Delegation {

        @Test
        void consumes_a_valid_letter_command_delegating_to_ingestion_with_reply_to_and_completes_the_message() {
            final UUID id = UUID.randomUUID();

            asb.sendToCommandQueue(aFirstClassSendLetterCommand()
                    .notificationId(id)
                    .clientContext("correspondence")
                    .build(), REPLY_TO);

            final ArgumentCaptor<SendLetterCommand> command = ArgumentCaptor.forClass(SendLetterCommand.class);
            verify(ingestionService, timeout(TIMEOUT.toMillis())).ingest(command.capture(), eq(REPLY_TO));
            assertThat(command.getValue().notificationId()).isEqualTo(id);
            assertThat(command.getValue().letterUrl()).isEqualTo(LETTER_URL);
            assertThat(command.getValue().postage()).isEqualTo("first");
            assertThat(command.getValue().clientContext()).isEqualTo("correspondence");
        }
    }

    @Nested
    class ValidationRejection {

        @Test
        void reports_a_letter_command_without_a_letter_url_to_the_reply_queue_and_does_not_delegate_it() {
            final String body = "{\"notificationId\":\"" + UUID.randomUUID() + "\"}";

            asb.sendToCommandQueue(body, REPLY_TO);

            final ArgumentCaptor<Set<ConstraintViolation<SendLetterCommand>>> violations = ArgumentCaptor.captor();
            verify(validationFailureNotifier, timeout(TIMEOUT.toMillis()))
                    .notifyValidationFailed(eq(REPLY_TO), violations.capture(), eq(body));
            assertThat(violations.getValue())
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("letterUrl");
            verifyNoInteractions(ingestionService);
        }

        @Test
        void completes_a_letter_command_that_fails_validation_rather_than_dead_lettering_it() {
            asb.sendToCommandQueue("{\"notificationId\":\"" + UUID.randomUUID() + "\"}", REPLY_TO);

            verify(validationFailureNotifier, timeout(TIMEOUT.toMillis()))
                    .notifyValidationFailed(eq(REPLY_TO), any(), any());
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                    .until(() -> asb.peekDeadLetter() == null);
            verify(validationFailureNotifier, times(1)).notifyValidationFailed(eq(REPLY_TO), any(), any());
        }

        @Test
        void reports_an_unparseable_message_to_the_reply_queue_and_does_not_delegate_it() {
            asb.sendToCommandQueue("this is not json", REPLY_TO);

            verify(validationFailureNotifier, timeout(TIMEOUT.toMillis()))
                    .notifyUnreadable(eq(REPLY_TO), any(), eq("this is not json"));
            verifyNoInteractions(ingestionService);
        }

        @Test
        void abandons_and_redelivers_a_letter_command_that_fails_validation_when_reporting_it_fails() {
            doThrow(new IllegalStateException("reply queue unavailable")).when(validationFailureNotifier)
                    .notifyValidationFailed(any(), any(), any());

            asb.sendToCommandQueue("{\"notificationId\":\"" + UUID.randomUUID() + "\"}", REPLY_TO);

            verify(validationFailureNotifier, timeout(TIMEOUT.toMillis()).atLeast(2))
                    .notifyValidationFailed(eq(REPLY_TO), any(), any());
            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);
            verifyNoInteractions(ingestionService);
        }

        @Test
        void abandons_and_redelivers_an_unparseable_message_when_reporting_it_fails() {
            doThrow(new IllegalStateException("reply queue unavailable")).when(validationFailureNotifier)
                    .notifyUnreadable(any(), any(), any());

            asb.sendToCommandQueue("this is not json", REPLY_TO);

            verify(validationFailureNotifier, timeout(TIMEOUT.toMillis()).atLeast(2))
                    .notifyUnreadable(eq(REPLY_TO), any(), any());
            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);
            verifyNoInteractions(ingestionService);
        }
    }

    @Nested
    class DeadLettering {

        @Test
        void dead_letters_and_does_not_delegate_a_letter_command_with_no_reply_to() {
            asb.sendToCommandQueue(aSendLetterCommand().notificationId(UUID.randomUUID()).build());

            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);
            assertThat(asb.peekDeadLetter().getDeadLetterReason()).isEqualTo(MISSING_REPLY_TO);

            verifyNoInteractions(ingestionService, validationFailureNotifier);
        }

        @Test
        void dead_letters_and_does_not_report_an_invalid_letter_command_with_no_reply_to() {
            asb.sendToCommandQueue("{\"notificationId\":\"" + UUID.randomUUID() + "\"}");

            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);

            verifyNoInteractions(ingestionService, validationFailureNotifier);
        }
    }

    @Nested
    class Redelivery {

        @Test
        void abandons_and_redelivers_the_message_when_ingestion_fails() {
            doThrow(new IllegalStateException("ingestion failed")).when(ingestionService)
                    .ingest(any(SendLetterCommand.class), any());

            asb.sendToCommandQueue(aSendLetterCommand().notificationId(UUID.randomUUID()).build(), REPLY_TO);

            verify(ingestionService, timeout(TIMEOUT.toMillis()).atLeast(2)).ingest(any(SendLetterCommand.class), any());
            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);
        }
    }

    @Nested
    class ContractConformance {

        @Test
        void the_letter_command_fixture_conforms_to_the_published_contract() {
            assertConformsTo(SEND_LETTER_COMMAND, load("fixtures/commands/send-letter.json", fixturePlaceHolders()));
        }

        @Test
        void the_first_class_letter_command_fixture_conforms_to_the_published_contract() {
            assertConformsTo(SEND_LETTER_COMMAND, load("fixtures/commands/send-first-class-letter.json", fixturePlaceHolders()));
        }

        @Test
        void a_letter_command_without_a_letter_url_does_not_conform_to_the_published_contract() {
            assertThat(violations(SEND_LETTER_COMMAND, "{\"notificationId\":\"" + UUID.randomUUID() + "\"}")).isNotEmpty();
        }

        private Map<String, String> fixturePlaceHolders() {
            return of(
                    "notificationId", UUID.randomUUID().toString(),
                    "letterUrl", LETTER_URL);
        }
    }
}
