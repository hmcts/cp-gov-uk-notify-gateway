package uk.gov.hmcts.cp.notification.result;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResultEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(ResultEventPublisher.class);

    private final String connectionString;
    private final String namespace;
    private final ObjectMapper objectMapper;

    // One thread-safe sender per reply queue, reused across result events; closed on shutdown.
    private final Map<String, ServiceBusSenderClient> sendersByQueue = new ConcurrentHashMap<>();

    public ResultEventPublisher(
            @Value("${cp.notification.servicebus.connection-string:}") final String connectionString,
            @Value("${cp.notification.servicebus.namespace:}") final String namespace,
            final ObjectMapper objectMapper) {
        this.connectionString = connectionString;
        this.namespace = namespace;
        this.objectMapper = objectMapper;
    }

    public void publish(final String replyQueue, final ResultEvent event) {
        if (!StringUtils.hasText(replyQueue)) {
            return;
        }

        final ServiceBusMessage message = new ServiceBusMessage(objectMapper.writeValueAsString(event));
        message.setSubject(event.eventName());

        sendersByQueue.computeIfAbsent(replyQueue, this::buildSender).sendMessage(message);
        LOG.info("Published {} to reply queue {}", event.eventName(), replyQueue);
    }

    private ServiceBusSenderClient buildSender(final String replyQueue) {
        return authenticate(new ServiceBusClientBuilder())
                .sender()
                .queueName(replyQueue)
                .buildClient();
    }

    private ServiceBusClientBuilder authenticate(final ServiceBusClientBuilder builder) {
        return StringUtils.hasText(connectionString)
                ? builder.connectionString(connectionString)
                : builder.fullyQualifiedNamespace(namespace)
                        .credential(new DefaultAzureCredentialBuilder().build());
    }

    @PreDestroy
    /* default */ void closeSenders() {
        sendersByQueue.values().forEach(ServiceBusSenderClient::close);
        sendersByQueue.clear();
    }
}
