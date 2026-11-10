package uk.gov.hmcts.cp.notification.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Active when Service Bus is configured, by connection string (emulator / local) or by namespace (Managed Identity).
 * With neither, the consumers are inactive, so local runs and tests that don't need Service Bus start cleanly.
 */
@Target({TYPE, METHOD})
@Retention(RUNTIME)
@Documented
@ConditionalOnExpression(
        "!'${cp.notification.servicebus.connection-string:}'.trim().isEmpty()"
                + " || !'${cp.notification.servicebus.namespace:}'.trim().isEmpty()")
public @interface ConditionalOnServiceBusConfigured {
}
