package uk.gov.hmcts.cp.notification.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The opposite of {@link ConditionalOnServiceBusConfigured}: active when neither a connection string nor a namespace
 * is configured.
 */
@Target({TYPE, METHOD})
@Retention(RUNTIME)
@Documented
@ConditionalOnExpression(
        "'${cp.notification.servicebus.connection-string:}'.trim().isEmpty()"
                + " && '${cp.notification.servicebus.namespace:}'.trim().isEmpty()")
public @interface ConditionalOnServiceBusNotConfigured {
}
