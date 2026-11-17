package uk.gov.hmcts.cp.notification.messaging;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Active when Service Bus is configured. With neither a connection string nor a namespace, the consumers are
 * inactive, so local runs and tests that don't need Service Bus start cleanly.
 */
@Target({TYPE, METHOD})
@Retention(RUNTIME)
@Documented
@Conditional(ServiceBusConfiguredCondition.class)
public @interface ConditionalOnServiceBusConfigured {
}
