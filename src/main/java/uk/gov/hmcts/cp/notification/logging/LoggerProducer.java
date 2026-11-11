package uk.gov.hmcts.cp.notification.logging;

import org.slf4j.Logger;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static java.util.Optional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_PROTOTYPE;

/**
 * Lets a {@link Logger} be injected like any other collaborator, so tests can mock it and check what is logged.
 *
 * <p>The logger is named after the class that asked for it. When it is asked for by a {@code @Bean} method, it is
 * named after the type that method creates, because that object is the one that will log: a logger passed into a
 * {@code @Bean QueueReachabilityCheck ...(Logger logger)} method logs as {@code QueueReachabilityCheck}, not as the
 * configuration class. Declare such a method's return type as the class that logs, not an interface it
 * implements, or the logger is named after the interface.
 *
 * <p>Only inject a logger through a constructor, field or {@code @Bean} method parameter. Looking one up with
 * {@code getBean(Logger.class)} or an {@code ObjectProvider} gives no injection point to name it after.
 */
@Configuration
public class LoggerProducer {

    @Bean
    @Scope(SCOPE_PROTOTYPE)
    public Logger logger(final InjectionPoint injectionPoint) {
        return getLogger(classThatLogs(injectionPoint));
    }

    private Class<?> classThatLogs(final InjectionPoint injectionPoint) {
        return ofNullable(injectionPoint.getMethodParameter())
                .map(MethodParameter::getMethod)
                .filter(requestingMethod -> requestingMethod.isAnnotationPresent(Bean.class))
                .<Class<?>>map(Method::getReturnType)
                .orElseGet(() -> injectionPoint.getMember().getDeclaringClass());
    }
}
