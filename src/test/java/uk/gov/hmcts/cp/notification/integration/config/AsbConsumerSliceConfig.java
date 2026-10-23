package uk.gov.hmcts.cp.notification.integration.config;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import uk.gov.hmcts.cp.notification.messaging.SendEmailConsumer;
import uk.gov.hmcts.cp.notification.messaging.SendLetterConsumer;
import uk.gov.hmcts.cp.notification.messaging.ServiceBusConfig;

@Configuration
@Import({ServiceBusConfig.class, SendEmailConsumer.class, SendLetterConsumer.class})
@ImportAutoConfiguration({JacksonAutoConfiguration.class, ValidationAutoConfiguration.class})
public class AsbConsumerSliceConfig {
}
