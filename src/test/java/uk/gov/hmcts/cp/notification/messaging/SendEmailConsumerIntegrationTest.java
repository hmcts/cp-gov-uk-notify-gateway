package uk.gov.hmcts.cp.notification.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
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
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ContractSchema.SEND_EMAIL_COMMAND;
import static uk.gov.hmcts.cp.notification.integration.stubs.support.ContractSchema.assertConformsTo;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommand;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.aSendEmailCommandWithMaterialUrl;

class SendEmailConsumerIntegrationTest extends AbstractAsbConsumerSliceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ASBTestClient asb = anAsbTestClient(ServiceBusContainerSupport.SLICE_COMMAND_QUEUE);

    @AfterEach
    void cleanUp() {
        asb.purgeDeadLetterQueue();
    }

    @Nested
    class Delegation {

        @Test
        void consumes_a_valid_command_delegating_to_ingestion_with_reply_to_and_completes_the_message() {
            final UUID id = UUID.randomUUID();

            asb.sendToCommandQueue(aSendEmailCommand()
                    .notificationId(id)
                    .sendToAddress("user@example.com")
                    .clientContext("mi-reportdata")
                    .build(), "ng-result-correspondence");

            final ArgumentCaptor<SendEmailCommand> command = ArgumentCaptor.forClass(SendEmailCommand.class);
            verify(ingestionService, timeout(TIMEOUT.toMillis()))
                    .ingest(command.capture(), eq("ng-result-correspondence"));
            assertThat(command.getValue().notificationId()).isEqualTo(id);
            assertThat(command.getValue().sendToAddress()).isEqualTo("user@example.com");
            assertThat(command.getValue().clientContext()).isEqualTo("mi-reportdata");
        }

        @Test
        void delegates_a_command_carrying_a_material_url_with_the_link_intact() {
            final UUID id = UUID.randomUUID();

            asb.sendToCommandQueue(aSendEmailCommandWithMaterialUrl().notificationId(id).build());

            final ArgumentCaptor<SendEmailCommand> command = ArgumentCaptor.forClass(SendEmailCommand.class);
            verify(ingestionService, timeout(TIMEOUT.toMillis())).ingest(command.capture(), isNull());
            assertThat(command.getValue().notificationId()).isEqualTo(id);
            assertThat(command.getValue().materialUrl()).isEqualTo(MATERIAL_URL);
        }

        @Test
        void delegates_with_no_reply_to_when_the_message_carries_none() {
            final UUID id = UUID.randomUUID();

            asb.sendToCommandQueue(aSendEmailCommand().notificationId(id).build());

            verify(ingestionService, timeout(TIMEOUT.toMillis())).ingest(any(SendEmailCommand.class), isNull());
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
        void dead_letters_and_does_not_delegate_a_command_that_fails_validation() {
            asb.sendToCommandQueue("{\"sendToAddress\":\"user@example.com\"}");

            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);

            verifyNoInteractions(ingestionService);
        }
    }

    @Nested
    class Redelivery {

        @Test
        void abandons_and_redelivers_the_message_when_ingestion_fails() {
            doThrow(new IllegalStateException("ingestion failed")).when(ingestionService)
                    .ingest(any(SendEmailCommand.class), any());

            asb.sendToCommandQueue(aSendEmailCommand().notificationId(UUID.randomUUID()).build());

            verify(ingestionService, timeout(TIMEOUT.toMillis()).atLeast(2)).ingest(any(SendEmailCommand.class), any());
            await().atMost(TIMEOUT).until(() -> asb.peekDeadLetter() != null);
        }
    }

    @Nested
    class ContractConformance {

        @Test
        void the_command_fixture_with_an_attachment_conforms_to_the_published_contract() {
            assertConformsTo(SEND_EMAIL_COMMAND, load("fixtures/commands/send-email-with-attachment.json", fixturePlaceHolders()));
        }

        @Test
        void the_command_fixture_without_an_attachment_conforms_to_the_published_contract() {
            assertConformsTo(SEND_EMAIL_COMMAND, load("fixtures/commands/send-email-missing-attachment.json", fixturePlaceHolders()));
        }

        @Test
        void the_command_fixture_with_a_material_url_conforms_to_the_published_contract() {
            assertConformsTo(SEND_EMAIL_COMMAND, load("fixtures/commands/send-email-with-material-url.json", fixturePlaceHolders()));
        }

        @Test
        void the_command_fixture_with_a_material_url_and_an_attachment_conforms_to_the_published_contract() {
            assertConformsTo(SEND_EMAIL_COMMAND,
                    load("fixtures/commands/send-email-with-material-url-and-attachment.json", fixturePlaceHolders()));
        }

        private Map<String, String> fixturePlaceHolders() {
            return of(
                    "notificationId", UUID.randomUUID().toString(),
                    "templateId", UUID.randomUUID().toString(),
                    "fileUri", "https://examplefilestore.blob.core.windows.net/example-container/report.csv",
                    "materialUrl", MATERIAL_URL);
        }
    }
}
