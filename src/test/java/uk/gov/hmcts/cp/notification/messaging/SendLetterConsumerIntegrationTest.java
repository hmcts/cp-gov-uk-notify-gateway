package uk.gov.hmcts.cp.notification.messaging;

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
import java.util.UUID;

import static java.util.Map.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
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

class SendLetterConsumerIntegrationTest extends AbstractAsbConsumerSliceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
                    .build(), "ng-result-correspondence");

            final ArgumentCaptor<SendLetterCommand> command = ArgumentCaptor.forClass(SendLetterCommand.class);
            verify(ingestionService, timeout(TIMEOUT.toMillis()))
                    .ingest(command.capture(), eq("ng-result-correspondence"));
            assertThat(command.getValue().notificationId()).isEqualTo(id);
            assertThat(command.getValue().letterUrl()).isEqualTo(LETTER_URL);
            assertThat(command.getValue().postage()).isEqualTo("first");
            assertThat(command.getValue().clientContext()).isEqualTo("correspondence");
        }

        @Test
        void delegates_with_no_reply_to_when_the_message_carries_none() {
            asb.sendToCommandQueue(aSendLetterCommand().notificationId(UUID.randomUUID()).build());

            verify(ingestionService, timeout(TIMEOUT.toMillis())).ingest(any(SendLetterCommand.class), isNull());
        }
    }

    @Nested
    class DeadLettering {

        @Test
        void dead_letters_and_does_not_delegate_an_unparseable_message() {
            asb.sendToCommandQueue("this is not json");

            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);

            verifyNoInteractions(ingestionService);
        }

        @Test
        void dead_letters_and_does_not_delegate_a_letter_command_without_a_letter_url() {
            asb.sendToCommandQueue("{\"notificationId\":\"" + UUID.randomUUID() + "\"}");

            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);

            verifyNoInteractions(ingestionService);
        }
    }

    @Nested
    class Redelivery {

        @Test
        void abandons_and_redelivers_the_message_when_ingestion_fails() {
            doThrow(new IllegalStateException("ingestion failed")).when(ingestionService)
                    .ingest(any(SendLetterCommand.class), any());

            asb.sendToCommandQueue(aSendLetterCommand().notificationId(UUID.randomUUID()).build());

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
