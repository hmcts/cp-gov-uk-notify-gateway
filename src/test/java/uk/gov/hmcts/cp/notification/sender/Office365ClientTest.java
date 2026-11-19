package uk.gov.hmcts.cp.notification.sender;

import static java.lang.Thread.interrupted;
import static java.net.URI.create;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailRequestFactory.aSendEmailRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;

@ExtendWith(MockitoExtension.class)
class Office365ClientTest {

    @Mock
    private HttpClient httpClient;

    @Test
    void should_fail_as_temporary_and_keep_the_thread_interrupted_when_interrupted_while_sending() throws Exception {
        final InterruptedException interruptedException = new InterruptedException("interrupted");
        doThrow(interruptedException).when(httpClient).send(any(), any());
        final Office365Settings office365Settings = new Office365Settings(
                create("https://office365.example.internal/send"), "subscription-key", ofSeconds(30), ofSeconds(300));
        final Office365Client office365Client = new Office365Client(
                httpClient,
                office365Settings,
                new Office365RequestBodyFactory(JsonMapper.builder().build()),
                new Office365ResponseReader(JsonMapper.builder().build()));

        final Office365Exception office365Exception =
                catchThrowableOfType(Office365Exception.class, () -> office365Client.send(aSendEmailRequest().build()));

        assertThat(interrupted()).isTrue();
        assertThat(office365Exception.isPermanent()).isFalse();
        assertThat(office365Exception.getMessage()).isEqualTo("Office 365 request was interrupted");
        assertThat(office365Exception.getCause()).isSameAs(interruptedException);
    }
}
