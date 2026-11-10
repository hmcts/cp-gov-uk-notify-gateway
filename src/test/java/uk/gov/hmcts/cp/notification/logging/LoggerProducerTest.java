package uk.gov.hmcts.cp.notification.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.core.MethodParameter;

import static org.assertj.core.api.Assertions.assertThat;

class LoggerProducerTest {
    private final LoggerProducer loggerProducer = new LoggerProducer();

    @Test
    void should_name_the_logger_after_the_class_whose_constructor_asks_for_it() throws Exception {
        final InjectionPoint constructorParameterInjectionPoint = new InjectionPoint(
                new MethodParameter(ClassWithALogger.class.getDeclaredConstructor(Logger.class), 0));

        final Logger producedLogger = loggerProducer.logger(constructorParameterInjectionPoint);

        assertThat(producedLogger.getName()).isEqualTo(ClassWithALogger.class.getName());
    }

    @Test
    void should_name_the_logger_after_the_class_whose_field_asks_for_it() throws Exception {
        final InjectionPoint fieldInjectionPoint =
                new InjectionPoint(ClassWithALogger.class.getDeclaredField("fieldLogger"));

        final Logger producedLogger = loggerProducer.logger(fieldInjectionPoint);

        assertThat(producedLogger.getName()).isEqualTo(ClassWithALogger.class.getName());
    }

    @Test
    void should_name_the_logger_after_the_type_a_bean_method_creates_when_that_method_asks_for_it() throws Exception {
        final InjectionPoint beanMethodParameterInjectionPoint = new InjectionPoint(new MethodParameter(
                ConfigurationThatCreatesAClassWithALogger.class.getDeclaredMethod("classWithALogger", Logger.class),
                0));

        final Logger producedLogger = loggerProducer.logger(beanMethodParameterInjectionPoint);

        assertThat(producedLogger.getName()).isEqualTo(ClassWithALogger.class.getName());
    }

    @Test
    void should_name_the_logger_after_the_declaring_class_when_an_ordinary_method_asks_for_it() throws Exception {
        final InjectionPoint ordinaryMethodParameterInjectionPoint = new InjectionPoint(new MethodParameter(
                ClassWithALogger.class.getDeclaredMethod("useLogger", Logger.class), 0));

        final Logger producedLogger = loggerProducer.logger(ordinaryMethodParameterInjectionPoint);

        assertThat(producedLogger.getName()).isEqualTo(ClassWithALogger.class.getName());
    }

    static class ClassWithALogger {
        private final Logger fieldLogger;

        ClassWithALogger(final Logger logger) {
            this.fieldLogger = logger;
        }

        void useLogger(final Logger logger) {
            logger.info("Only its parameter matters to these tests; it is never called");
        }
    }

    static class ConfigurationThatCreatesAClassWithALogger {

        @Bean
        ClassWithALogger classWithALogger(final Logger logger) {
            return new ClassWithALogger(logger);
        }
    }
}
