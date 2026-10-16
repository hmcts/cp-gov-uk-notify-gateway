package uk.gov.hmcts.cp.notification.material;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.cp.notification.integration.testdata.MaterialSettingsFactory.MATERIAL_BASE_URL;
import static uk.gov.hmcts.cp.notification.integration.testdata.SendEmailCommandFactory.MATERIAL_URL;

@ExtendWith(MockitoExtension.class)
class MaterialUrlResolverTest {
    private static final String REFUSED = "Material link is not on the Material service";
    private static final URI REWRITTEN = URI.create(MATERIAL_BASE_URL + "/query/api/rest/material/material/x");

    @Mock
    private MaterialHostMatcher hostMatcher;

    @Mock
    private LoopbackMaterialLinkRewriter loopbackRewriter;

    @InjectMocks
    private MaterialUrlResolver resolver;

    private void assertRefused(final String link) {
        assertThatThrownBy(() -> resolver.resolve(link))
                .isInstanceOf(MaterialAttachmentException.class)
                .hasMessage(REFUSED)
                .hasFieldOrPropertyWithValue("statusCode", null)
                .hasFieldOrPropertyWithValue("retryable", false)
                .hasNoCause();
    }

    @Nested
    class WhenTheLinkIsOnTheMaterialHost {

        @Test
        void should_use_it_exactly_as_sent_without_rewriting() {
            when(hostMatcher.isOnMaterialHost(URI.create(MATERIAL_URL))).thenReturn(true);

            assertThat(resolver.resolve(MATERIAL_URL)).isEqualTo(URI.create(MATERIAL_URL));
            verifyNoInteractions(loopbackRewriter);
        }

        @Test
        void should_ignore_whitespace_around_the_link() {
            when(hostMatcher.isOnMaterialHost(URI.create(MATERIAL_URL))).thenReturn(true);

            assertThat(resolver.resolve("  " + MATERIAL_URL + " ")).isEqualTo(URI.create(MATERIAL_URL));
        }
    }

    @Nested
    class WhenTheLinkIsTheOneMaterialsClientLibraryBuilds {

        @Test
        void should_use_the_rewritten_link() {
            final URI link = URI.create(MATERIAL_URL);
            when(hostMatcher.isOnMaterialHost(link)).thenReturn(false);
            when(loopbackRewriter.canRewrite(link)).thenReturn(true);
            when(loopbackRewriter.rewrite(link)).thenReturn(REWRITTEN);

            assertThat(resolver.resolve(MATERIAL_URL)).isEqualTo(REWRITTEN);
        }
    }

    @Nested
    class WhenTheLinkIsNotOnTheMaterialService {

        @Test
        void should_refuse_a_link_that_is_neither_on_the_material_host_nor_rewritable() {
            when(hostMatcher.isOnMaterialHost(any())).thenReturn(false);
            when(loopbackRewriter.canRewrite(any())).thenReturn(false);

            assertRefused("http://evil.example.com/material-query-api/query");
            verify(loopbackRewriter, never()).rewrite(any());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/material-query-api/query/api/rest/material/material/x",
                "http:///material-query-api/query/api/rest/material/material/x",
                "mailto:someone@example.com",
                "not a url at all"})
        void should_refuse_a_link_without_a_scheme_or_host_before_checking_where_it_points(final String link) {
            assertRefused(link);
            verifyNoInteractions(hostMatcher, loopbackRewriter);
        }
    }
}
