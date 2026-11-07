package com.gustler.backend.worker.routegeometry.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gustler.backend.routecatalog.api.ReadRouteModelFacts;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.worker.routegeometry.JdbcRouteDisplayGenerationStore;
import com.gustler.backend.worker.routegeometry.RouteDisplaySourceReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Spring wiring only: no server, DB, source acquisition or candidate execution. */
class ReferenceShadowConfigurationTest {
    private static final String PREFIX = "route-geometry.generation.";
    // Pins are independently fixed from the source-only v2 fixture, not taken from properties or packet fields.
    private static final String BUNDLE_SHA = "f3d92ca776f7a60043b0f087b49a815c333930cf71138a70494c43d948daf503";
    private static final String GZIP_SHA = "d580c5a8a941ba2906ef68ad804fd6f93ec028633755511a805610a19d2423ab";
    private static final String RAW_SHA = "3898bf3e38e664c13ebbb81f3bf716e8bb2522a43ae54644ddc843b826b64aa9";
    private static final Set<String> MODEL_INPUTS = Set.of(
            "3898bf3e38e664c13ebbb81f3bf716e8bb2522a43ae54644ddc843b826b64aa9",
            "4058867f71541ecdc85df658492ab912830b945535ffc0eb70b4c54d51b9f804",
            "467c149e463a04efc265b6b8344e861c505bac5af83bf5b1a5f03d2de1cc9c2e",
            "4d148b453ca98acb153c6ada92d393bd59bdf7aac4b29f6885783cfd6bacc66b",
            "594a924cbbc1b84cd1acfcb7f54be9d4a750d457149db1921da293c4fcd1c74e",
            "9413c07071459ac567f90f6ea0bc7dbe8f418bae3d7306d3773e928a5360d0f7",
            "a40b416e9df151c155f3170eed434faea46d8ec67f5e81f6801992eec87c1869",
            "a5c88f08738421050881304d90a0fe96855ffda31308a5beb4bff455efcc3215",
            "a93bb1ef5017bb383956be1c9732fbf85b1cfe022e1ed4c73bed8db1802faa99",
            "ae45e7b13e117a96c168cffdaf39e1e05500d189076cf0bd4e9cb7f56cefa941",
            "bcc6d2ae8b36a0634d2e1479ac3f0fd7de58bb144bfc2d3987fbc6a8fbc4b734",
            "df58a16f29fd09a9cdf2bb314582d51928aa1ec54389225cec792afc803c20dd",
            "efb7f059f5b568dde49fbf2632290cc89f8d97cd992fedf462e46a4851baf6da");

    private final JdbcRouteDisplayGenerationStore store = mock(JdbcRouteDisplayGenerationStore.class);
    private final RouteDisplaySourceReader sources = mock(RouteDisplaySourceReader.class);
    private final ReadRouteModelFacts facts = mock(ReadRouteModelFacts.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private ApplicationContextRunner bare() {
        // Match SpringApplication's environment/BeanFactory conversion setup without starting a server.
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    context.getEnvironment().setConversionService(new ApplicationConversionService());
                    context.getBeanFactory().setConversionService(context.getEnvironment().getConversionService());
                })
                .withUserConfiguration(ReferenceShadowConfiguration.class);
    }

    private ApplicationContextRunner dependencies() {
        return bare()
                .withBean(JdbcRouteDisplayGenerationStore.class, () -> store)
                .withBean(RouteDisplaySourceReader.class, () -> sources)
                .withBean(ReadRouteModelFacts.class, () -> facts)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(PlatformTransactionManager.class, () -> transactions);
    }

    private static Path fixture() throws IOException {
        var resource = ReferenceShadowConfigurationTest.class.getResource("/reference/native-search-v2.json.gz");
        assertThat(resource).isNotNull();
        final Path path;
        try {
            path = Path.of(resource.toURI());
        } catch (java.net.URISyntaxException failure) {
            throw new IOException(failure);
        }
        assertThat(RouteSourceReceipt.sha256(Files.readAllBytes(path))).isEqualTo(GZIP_SHA);
        try (var input = new GZIPInputStream(Files.newInputStream(path))) {
            assertThat(RouteSourceReceipt.sha256(input.readAllBytes())).isEqualTo(BUNDLE_SHA);
        }
        return path;
    }

    private ApplicationContextRunner enabled() throws IOException {
        return dependencies().withPropertyValues(
                PREFIX + "enabled=true",
                PREFIX + "shadow.enabled=true",
                PREFIX + "shadow.bundle-path=" + fixture(),
                PREFIX + "shadow.bundle-sha256=" + BUNDLE_SHA,
                PREFIX + "shadow.raw-source-sha256=" + RAW_SHA,
                PREFIX + "shadow.model-input-sha256=" + String.join(",", MODEL_INPUTS));
    }

    @Test
    void default_flags_need_neither_a_bundle_nor_worker_dependencies() {
        bare().run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ReferenceShadowGeneration.class));
    }

    @Test
    void both_flags_off_ignore_missing_bundle_settings() {
        bare().withPropertyValues(PREFIX + "enabled=false", PREFIX + "shadow.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ReferenceShadowGeneration.class));
    }

    @Test
    void shadow_flag_alone_cannot_enable_generation() {
        bare().withPropertyValues(PREFIX + "enabled=false", PREFIX + "shadow.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ReferenceShadowGeneration.class));
    }

    @Test
    void generation_flag_alone_does_not_require_shadow_bundle_settings() {
        bare().withPropertyValues(PREFIX + "enabled=true", PREFIX + "shadow.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ReferenceShadowGeneration.class));
    }

    @Test
    void both_flags_bind_the_server_pinned_gzip_and_full_model_input_set_without_a_clock_bean() throws IOException {
        enabled().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ReferenceShadowGeneration.class);
            verifyNoInteractions(store, sources, facts, transactions);
        });
    }

    @Test
    void explicitly_supplied_clock_is_supported_without_starting_a_job() throws IOException {
        Clock clock = Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC);
        enabled().withBean(Clock.class, () -> clock).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ReferenceShadowGeneration.class);
            assertThat(context.getBean(Clock.class)).isSameAs(clock);
            verifyNoInteractions(store, sources, facts, transactions);
        });
    }

    @Test
    void a_compressed_file_digest_cannot_replace_the_pinned_decompressed_bundle_digest() throws IOException {
        enabled().withPropertyValues(PREFIX + "shadow.bundle-sha256=" + GZIP_SHA).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("SEARCH_HASH_MISMATCH");
            verifyNoInteractions(store, sources, facts, transactions);
        });
    }

    @Test
    void a_wrong_server_raw_source_pin_fails_context_creation() throws IOException {
        enabled().withPropertyValues(PREFIX + "shadow.raw-source-sha256=" + "0".repeat(64)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("RAW_SOURCE_PIN_MISMATCH");
            verifyNoInteractions(store, sources, facts, transactions);
        });
    }

    @Test
    void an_incomplete_property_bound_model_input_set_fails_context_creation() throws IOException {
        enabled().withPropertyValues(PREFIX + "shadow.model-input-sha256=" + RAW_SHA).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("MODEL_INPUT_PIN_MISMATCH");
            verifyNoInteractions(store, sources, facts, transactions);
        });
    }

    @Test
    void both_enabled_without_required_server_pins_fail_context_creation() {
        dependencies().withPropertyValues(PREFIX + "enabled=true", PREFIX + "shadow.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            verifyNoInteractions(store, sources, facts, transactions);
        });
    }
}
