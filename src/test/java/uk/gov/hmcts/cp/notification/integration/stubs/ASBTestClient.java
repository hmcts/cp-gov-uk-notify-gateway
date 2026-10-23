package uk.gov.hmcts.cp.notification.integration.stubs;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.messaging.servicebus.models.SubQueue;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import uk.gov.hmcts.cp.notification.command.SendEmailCommand;
import uk.gov.hmcts.cp.notification.command.SendLetterCommand;
import uk.gov.hmcts.cp.notification.integration.stubs.support.ServiceBusContainerSupport;

public final class ASBTestClient {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final String queueName;

    private ASBTestClient(final String queueName) {
        this.queueName = queueName;
    }

    public static ASBTestClient anAsbTestClient() {
        return new ASBTestClient(ServiceBusContainerSupport.COMMAND_QUEUE);
    }

    public static ASBTestClient anAsbTestClient(final String queueName) {
        return new ASBTestClient(queueName);
    }

    public static ASBTestClient aLetterAsbTestClient() {
        return new ASBTestClient(ServiceBusContainerSupport.LETTER_COMMAND_QUEUE);
    }

    public ASBTestClient sendToCommandQueue(final SendEmailCommand command) {
        return sendToCommandQueue(MAPPER.writeValueAsString(command));
    }

    public ASBTestClient sendToCommandQueue(final SendLetterCommand command) {
        return sendToCommandQueue(MAPPER.writeValueAsString(command));
    }

    public ASBTestClient sendToCommandQueue(final SendLetterCommand command, final String replyTo) {
        return sendToCommandQueue(MAPPER.writeValueAsString(command), replyTo);
    }

    public ASBTestClient sendToCommandQueue(final SendEmailCommand command, final String replyTo) {
        return sendToCommandQueue(MAPPER.writeValueAsString(command), replyTo);
    }

    public ASBTestClient sendToCommandQueue(final String body) {
        return sendToCommandQueue(body, null);
    }

    public ASBTestClient sendToCommandQueue(final String body, final String replyTo) {
        final ServiceBusMessage message = new ServiceBusMessage(body);
        if (replyTo != null) {
            message.setReplyTo(replyTo);
        }
        try (ServiceBusSenderClient sender = senderBuilder().buildClient()) {
            sender.sendMessage(message);
        }
        return this;
    }

    public ASBTestClient send(final String body) {
        try (ServiceBusSenderClient sender = senderBuilder().buildClient()) {
            sender.sendMessage(new ServiceBusMessage(body));
        }
        return this;
    }

    public ServiceBusReceivedMessage receiveMessage(final Duration timeout) {
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder()
                .connectionString(ServiceBusContainerSupport.getConnectionString())
                .receiver()
                .receiveMode(ServiceBusReceiveMode.RECEIVE_AND_DELETE)
                .queueName(queueName)
                .buildClient()) {
            return receiver.receiveMessages(1, timeout).stream().findFirst().orElse(null);
        }
    }

    public ASBTestClient purgeDeadLetterQueue() {
        if (peekDeadLetter() == null) {
            return this;
        }
        try (ServiceBusReceiverClient dlq = deadLetterReceiverBuilder()
                .receiveMode(ServiceBusReceiveMode.RECEIVE_AND_DELETE)
                .buildClient()) {
            long drained;
            do {
                drained = dlq.receiveMessages(50, Duration.ofMillis(200)).stream().count();
            } while (drained > 0);
        }
        return this;
    }

    public ServiceBusReceivedMessage peekDeadLetter() {
        try (ServiceBusReceiverClient dlq = deadLetterReceiverBuilder().buildClient()) {
            return dlq.peekMessage();
        }
    }

    private ServiceBusClientBuilder.ServiceBusSenderClientBuilder senderBuilder() {
        return new ServiceBusClientBuilder()
                .connectionString(ServiceBusContainerSupport.getConnectionString())
                .sender()
                .queueName(queueName);
    }

    private ServiceBusClientBuilder.ServiceBusReceiverClientBuilder deadLetterReceiverBuilder() {
        return new ServiceBusClientBuilder()
                .connectionString(ServiceBusContainerSupport.getConnectionString())
                .receiver()
                .queueName(queueName)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE);
    }
}
