package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.gbis.api.GbisKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

class GbisPropertiesTest {

    private static final String BASE_URL = "https://gbis.test";
    private static final int DAILY_LIMIT = 10_000;

    /** 2025-08-21 전에 발급된 인증키는 base64 라 {@code +} {@code /} {@code =} 가 섞인다. */
    private static final String ORIGINAL_KEY = "ab+cd/ef=";
    private static final String PERCENT_KEY = "ab%2Bcd%2Fef%3D";
    private static final String KEY_TAIL = "SECRETTAIL";
    private static final String KEY_B = "fake-key-b";
    private static final String KEY_D = "fake-key-d";

    @Test
    void 퍼센트로_바뀐_인증키를_원래_값으로_되돌린다() {
        GbisProperties actual = new GbisProperties(BASE_URL, PERCENT_KEY, DAILY_LIMIT);

        assertThat(actual.serviceKey()).isEqualTo(ORIGINAL_KEY);
    }

    @Test
    void 원래_값으로_받은_인증키는_그대로_둔다() {
        GbisProperties actual = new GbisProperties(BASE_URL, ORIGINAL_KEY, DAILY_LIMIT);

        assertThat(actual.serviceKey()).isEqualTo(ORIGINAL_KEY);
    }

    @Test
    void 퍼센트_형식이_깨진_인증키는_기동을_막는다() {
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, "ab%G0" + KEY_TAIL, DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 퍼센트_형식이_깨져도_인증키가_예외에_남지_않는다() {
        Throwable thrown = catchThrowableOf("ab%G0" + KEY_TAIL);

        assertThat(messagesOf(thrown)).doesNotContain(KEY_TAIL);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 인증키가_없거나_비었거나_공백뿐이면_기동을_막는다(
        String serviceKey
    ) {
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, serviceKey, DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void 인증키가_비었거나_공백뿐이면_애플리케이션_컨텍스트가_뜨지_않는다(
        String serviceKey
    ) {
        contextRunner()
            .withPropertyValues(
                "gbis.base-url=" + BASE_URL,
                "gbis.service-key=" + serviceKey,
                "gbis.daily-limit=" + DAILY_LIMIT)
            .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 환경변수가 아예 없는 상태를 만든다.
     *
     * <p>테스트 JVM 에서 GBIS_SERVICE_KEY 를 지울 수는 없다. build.gradle 이 넣고,
     * 스프링은 그 환경변수를 gbis.service-key 에 바로 붙인다. 그래서 환경변수 원본을 안 보는
     * Environment 를 만들어 application.yml 만 얹고 자리표시자가 무엇으로 풀리는지 본다.
     */
    private String serviceKeyWithoutEnvironmentVariable() {
        return propertyWithoutEnvironmentVariable("gbis.service-key");
    }

    private String propertyWithoutEnvironmentVariable(
        String name
    ) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        try {
            new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return environment.getProperty(name);
    }

    @Test
    void 환경변수가_없으면_인증키가_빈_값으로_풀린다() {
        assertThat(serviceKeyWithoutEnvironmentVariable()).isEmpty();
    }

    @Test
    void 환경변수가_없으면_기동을_막는다() {
        String resolved = serviceKeyWithoutEnvironmentVariable();

        assertThatThrownBy(() -> new GbisProperties(BASE_URL, resolved, DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 인증키가_있으면_애플리케이션_컨텍스트가_뜬다() {
        contextRunner()
            .withPropertyValues(
                "gbis.base-url=" + BASE_URL,
                "gbis.service-key=" + PERCENT_KEY,
                "gbis.daily-limit=" + DAILY_LIMIT)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 값이_있는_슬롯만_a부터_순서대로_쓴다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, ORIGINAL_KEY, KEY_B, "   ", KEY_D, DAILY_LIMIT);

        // when
        List<String> aliases = aliasesOf(properties);

        // then
        assertThat(aliases).containsExactly(GbisKey.PRIMARY, "b", "d");
    }

    @Test
    void 추가_슬롯의_인증키도_퍼센트를_원래_값으로_되돌린다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, KEY_B, PERCENT_KEY, null, null, DAILY_LIMIT);

        // when
        String serviceKey = serviceKeyOf(properties, "b");

        // then
        assertThat(serviceKey).isEqualTo(ORIGINAL_KEY);
    }

    @Test
    void 추가_슬롯의_퍼센트_형식이_깨지면_기동을_막는다() {
        // when & then
        assertThatThrownBy(
            () -> new GbisProperties(BASE_URL, ORIGINAL_KEY, "ab%G0" + KEY_TAIL, null, null, DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gbis.service-key-b")
            .hasMessageNotContaining(KEY_TAIL);
    }

    @Test
    void 문자열로_바꾸면_모든_슬롯의_인증키를_가린다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, ORIGINAL_KEY, KEY_B, null, KEY_D, DAILY_LIMIT);

        // when
        String actual = properties.toString();

        // then
        assertThat(actual)
            .doesNotContain(ORIGINAL_KEY)
            .doesNotContain(KEY_B)
            .doesNotContain(KEY_D);
    }

    @Test
    void 추가_키를_설정하면_애플리케이션_컨텍스트가_슬롯을_읽는다() {
        // when & then
        contextRunner()
            .withPropertyValues(
                "gbis.base-url=" + BASE_URL,
                "gbis.service-key=" + PERCENT_KEY,
                "gbis.service-key-b=" + KEY_B,
                "gbis.daily-limit=" + DAILY_LIMIT)
            .run(context -> assertThat(aliasesOf(context.getBean(GbisProperties.class)))
                .containsExactly(GbisKey.PRIMARY, "b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gbis.service-key-b", "gbis.service-key-c", "gbis.service-key-d"})
    void 환경변수가_없으면_추가_슬롯은_빈_값으로_풀린다(
        String property
    ) {
        // when
        String resolved = propertyWithoutEnvironmentVariable(property);

        // then
        assertThat(resolved).isEmpty();
    }

    @Test
    void 수집을_꺼도_인증키가_없으면_애플리케이션_컨텍스트가_뜨지_않는다() {
        // when & then
        collectionConfigRunner()
            .withPropertyValues(
                "collection.enabled=false",
                "gbis.base-url=" + BASE_URL,
                "gbis.service-key=",
                "gbis.daily-limit=" + DAILY_LIMIT)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 수집을_꺼도_인증키를_읽는다() {
        // when & then
        collectionConfigRunner()
            .withPropertyValues(
                "collection.enabled=false",
                "gbis.base-url=" + BASE_URL,
                "gbis.service-key=" + PERCENT_KEY,
                "gbis.daily-limit=" + DAILY_LIMIT)
            .run(context -> assertThat(context.getBean(GbisProperties.class).serviceKey()).isEqualTo(ORIGINAL_KEY));
    }

    @Test
    void 새_형식으로_키를_다섯_개_넣으면_a를_먼저_두고_별칭_순서대로_모두_쓴다() {
        // given
        GbisProperties properties = keysOnly(Map.of(
            "e", "fake-key-e", "a", "fake-key-a", "c", "fake-key-c", "b", "fake-key-b", "d", "fake-key-d"));

        // when
        List<String> aliases = aliasesOf(properties);

        // then
        assertThat(aliases).containsExactly(GbisKey.PRIMARY, "b", "c", "d", "e");
        assertThat(serviceKeyOf(properties, "e")).isEqualTo("fake-key-e");
    }

    @Test
    void 기존_슬롯_네_개와_새_형식의_키를_함께_쓰면_합쳐서_쓴다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, ORIGINAL_KEY, KEY_B, "fake-key-c", KEY_D,
            Map.of("e", "fake-key-e", "f10", "fake-key-f10"), DAILY_LIMIT);

        // when
        List<String> aliases = aliasesOf(properties);

        // then
        assertThat(aliases).containsExactly(GbisKey.PRIMARY, "b", "c", "d", "e", "f10");
        assertThat(serviceKeyOf(properties, GbisKey.PRIMARY)).isEqualTo(ORIGINAL_KEY);
    }

    @Test
    void 새_형식만으로_기본_키_a를_주면_기존_변수_없이도_뜬다() {
        // given
        GbisProperties properties = keysOnly(Map.of("a", PERCENT_KEY, "e", "fake-key-e"));

        // when
        String primary = properties.serviceKey();

        // then
        assertThat(primary).isEqualTo(ORIGINAL_KEY);
        assertThat(aliasesOf(properties)).containsExactly(GbisKey.PRIMARY, "e");
    }

    @Test
    void 설정에_적은_순서가_달라도_별칭과_키의_짝은_같다() {
        // given
        Map<String, String> forward = new java.util.LinkedHashMap<>();
        forward.put("a", "fake-key-a");
        forward.put("e", "fake-key-e");
        forward.put("b", "fake-key-b");
        Map<String, String> backward = new java.util.LinkedHashMap<>();
        backward.put("b", "fake-key-b");
        backward.put("e", "fake-key-e");
        backward.put("a", "fake-key-a");

        // when
        List<GbisKey> first = keysOnly(forward).serviceKeys();
        List<GbisKey> second = keysOnly(backward).serviceKeys();

        // then
        assertThat(first).isEqualTo(second);
    }

    @Test
    void 기본_키_a가_없으면_새_형식의_다른_키가_있어도_기동을_막는다() {
        // when & then
        assertThatThrownBy(() -> keysOnly(Map.of("e", "fake-key-e")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GBIS_KEYS_A")
            .hasMessageNotContaining("fake-key-e");
    }

    @Test
    void 같은_별칭을_기존_변수와_새_형식에서_함께_정하면_기동을_막는다() {
        // when & then
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, ORIGINAL_KEY, KEY_B, null, null,
            Map.of("b", "fake-key-other"), DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gbis.keys.b")
            .hasMessageNotContaining(KEY_B)
            .hasMessageNotContaining("fake-key-other");
    }

    @Test
    void 같은_인증키를_두_별칭에_넣으면_어느_형식이든_기동을_막는다() {
        // when & then
        assertThatThrownBy(() -> keysOnly(Map.of("a", "fake-key-a", "e", "fake-key-a")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("a,e")
            .hasMessageNotContaining("fake-key-a");
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, ORIGINAL_KEY, KEY_B, null, null,
            Map.of("e", KEY_B), DAILY_LIMIT))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("b,e")
            .hasMessageNotContaining(KEY_B);
    }

    @Test
    void 퍼센트로_바꾼_값과_원래_값이_같은_키면_중복으로_막는다() {
        // when & then
        assertThatThrownBy(() -> keysOnly(Map.of("a", ORIGINAL_KEY, "e", PERCENT_KEY)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining(ORIGINAL_KEY)
            .hasMessageNotContaining(PERCENT_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void 새_형식의_값이_비었으면_기동을_막는다(
        String serviceKey
    ) {
        // when & then
        assertThatThrownBy(() -> keysOnly(Map.of("a", "fake-key-a", "e", serviceKey)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gbis.keys.e");
    }

    @ParameterizedTest
    @ValueSource(strings = {"E", "e-1", "e_1", "e.1", "abcdefghijklmnopq"})
    void 별칭이_소문자와_숫자_16자_규칙에_맞지_않으면_기동을_막는다(
        String alias
    ) {
        // when & then
        assertThatThrownBy(() -> keysOnly(Map.of("a", "fake-key-a", alias, "fake-key-x")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("fake-key-x");
    }

    @Test
    void 새_형식의_퍼센트_형식이_깨지면_값_없이_별칭만_알린다() {
        // when & then
        assertThatThrownBy(() -> keysOnly(Map.of("a", "fake-key-a", "e", "ab%G0" + KEY_TAIL)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gbis.keys.e")
            .hasMessageNotContaining(KEY_TAIL);
    }

    @Test
    void 문자열로_바꾸면_새_형식의_키도_가리고_별칭만_보인다() {
        // given
        GbisProperties properties = keysOnly(Map.of("a", "fake-key-a", "e", "fake-key-e"));

        // when
        String actual = properties.toString();

        // then
        assertThat(actual).contains("a,e").doesNotContain("fake-key-a").doesNotContain("fake-key-e");
    }

    @Test
    void 환경변수_GBIS_KEYS_별칭으로_새_키를_읽는다() {
        // when & then
        environmentRunner(Map.of(
            "GBIS_BASE_URL", BASE_URL,
            "GBIS_SERVICE_KEY", "fake-key-a",
            "GBIS_SERVICE_KEY_B", "fake-key-b",
            "GBIS_KEYS_E", "fake-key-e",
            "GBIS_KEYS_F10", "fake-key-f10"))
            .run(context -> {
                GbisProperties properties = context.getBean(GbisProperties.class);
                assertThat(aliasesOf(properties)).containsExactly(GbisKey.PRIMARY, "b", "e", "f10");
                assertThat(serviceKeyOf(properties, "f10")).isEqualTo("fake-key-f10");
            });
    }

    @Test
    void 환경변수를_새_형식으로만_주어도_기동한다() {
        // when & then
        environmentRunner(Map.of(
            "GBIS_BASE_URL", BASE_URL,
            "GBIS_KEYS_A", "fake-key-a",
            "GBIS_KEYS_E", "fake-key-e"))
            .run(context -> assertThat(aliasesOf(context.getBean(GbisProperties.class)))
                .containsExactly(GbisKey.PRIMARY, "e"));
    }

    @Test
    void 환경변수의_별칭이_기존_변수와_겹치면_키_값을_남기지_않고_기동을_막는다() {
        // when & then
        environmentRunner(Map.of(
            "GBIS_BASE_URL", BASE_URL,
            "GBIS_SERVICE_KEY", "fake-key-a",
            "GBIS_KEYS_A", "fake-key-other"))
            .run(context -> assertThat(context).getFailure()
                .satisfies(failure -> assertThat(messagesOf(failure))
                    .contains("gbis.keys.a")
                    .doesNotContain("fake-key-a")
                    .doesNotContain("fake-key-other")));
    }

    @Test
    void 키_목록을_한_줄로_넣으면_값을_남기지_않고_기동을_막는다() {
        // when & then
        environmentRunner(Map.of(
            "GBIS_BASE_URL", BASE_URL,
            "GBIS_SERVICE_KEY", "fake-key-a",
            "GBIS_KEYS", "e=fake-key-inline"))
            .run(context -> assertThat(context).getFailure()
                .satisfies(failure -> assertThat(messagesOf(failure)).doesNotContain("fake-key-inline")));
    }

    @Test
    void 키별_한도를_주면_그_키만_그_한도를_쓰고_나머지는_공통_한도를_쓴다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, ORIGINAL_KEY, null, null, null,
            Map.of("e", "fake-key-e"), DAILY_LIMIT, Map.of("e", 1_000));

        // when & then
        assertThat(properties.dailyLimitOf("e")).isEqualTo(1_000);
        assertThat(properties.dailyLimitOf(GbisKey.PRIMARY)).isEqualTo(DAILY_LIMIT);
    }

    @Test
    void 설정에_없는_별칭에_한도를_주면_기동을_막는다() {
        // when & then
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, ORIGINAL_KEY, null, null, null,
            Map.of("e", "fake-key-e"), DAILY_LIMIT, Map.of("f", 1_000)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gbis.limits.f");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 키별_한도가_1보다_작으면_기동을_막는다(
        final int limit
    ) {
        // when & then
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, ORIGINAL_KEY, null, null, null,
            Map.of("e", "fake-key-e"), DAILY_LIMIT, Map.of("e", limit)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gbis.limits.e");
    }

    @Test
    void 환경변수_GBIS_LIMITS_별칭으로_키별_한도를_읽는다() {
        // when & then
        environmentRunner(Map.of(
            "GBIS_BASE_URL", BASE_URL,
            "GBIS_SERVICE_KEY", "fake-key-a",
            "GBIS_KEYS_E", "fake-key-e",
            "GBIS_LIMITS_E", "1000"))
            .run(context -> {
                GbisProperties properties = context.getBean(GbisProperties.class);
                assertThat(properties.dailyLimitOf("e")).isEqualTo(1_000);
                assertThat(properties.dailyLimitOf(GbisKey.PRIMARY)).isEqualTo(DAILY_LIMIT);
            });
    }

    @Test
    void GBIS_SERVICE_KEY_별칭으로_받은_키를_기존_슬롯_뒤에_별칭_순서대로_쓴다() {
        // given
        GbisProperties properties = namedOnly(Map.of("g", "fake-key-g", "e", "fake%2Bkey-e", "f", "fake-key-f"));

        // when
        List<String> aliases = aliasesOf(properties);

        // then
        assertThat(aliases).containsExactly(GbisKey.PRIMARY, "b", "d", "e", "f", "g");
        assertThat(serviceKeyOf(properties, "e")).isEqualTo("fake+key-e");
    }

    @Test
    void 같은_별칭을_GBIS_SERVICE_KEY_별칭과_새_형식에서_함께_정하면_기동을_막는다() {
        // when & then
        assertThatThrownBy(() -> new GbisProperties(BASE_URL, "fake-key-a", null, null, null,
            Map.of("e", "fake-key-other"), DAILY_LIMIT, Map.of(), Map.of("e", "fake-key-e")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("gbis.keys.e")
            .hasMessageNotContaining("fake-key-e")
            .hasMessageNotContaining("fake-key-other");
    }

    @Test
    void GBIS_SERVICE_KEY_A로_기본_키를_한_번_더_정하면_기동을_막는다() {
        // when & then
        assertThatThrownBy(() -> namedOnly(Map.of("a", "fake-key-other")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GBIS_SERVICE_KEY_A")
            .hasMessageNotContaining("fake-key-other");
    }

    @Test
    void GBIS_SERVICE_KEY_별칭이_영문과_숫자_16자_규칙에_맞지_않으면_기동을_막는다() {
        // when & then
        assertThatThrownBy(() -> namedOnly(Map.of("team_5", "fake-key-team5")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GBIS_SERVICE_KEY_TEAM_5")
            .hasMessageNotContaining("fake-key-team5");
    }

    @Test
    void GBIS_SERVICE_KEY_별칭의_퍼센트_형식이_깨지면_값_없이_변수_이름만_알린다() {
        // when & then
        assertThatThrownBy(() -> namedOnly(Map.of("e", "ab%G0" + KEY_TAIL)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GBIS_SERVICE_KEY_E")
            .hasMessageNotContaining(KEY_TAIL);
    }

    @Test
    void GBIS_SERVICE_KEY_별칭의_값이_비었으면_기동을_막는다() {
        // when & then
        assertThatThrownBy(() -> namedOnly(Map.of("e", " ")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GBIS_SERVICE_KEY_E");
    }

    private static GbisProperties namedOnly(
        Map<String, String> named
    ) {
        return new GbisProperties(BASE_URL, ORIGINAL_KEY, KEY_B, null, KEY_D, Map.of(), DAILY_LIMIT, Map.of(), named);
    }

    private static GbisProperties keysOnly(
        Map<String, String> keys
    ) {
        return new GbisProperties(BASE_URL, null, null, null, null, keys, DAILY_LIMIT);
    }

    private ApplicationContextRunner environmentRunner(
        Map<String, String> variables
    ) {
        Map<String, Object> environment = new java.util.HashMap<>(variables);
        environment.putIfAbsent("GBIS_DAILY_LIMIT", Integer.toString(DAILY_LIMIT));
        return contextRunner().withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.addLast(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
        });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(
                org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableGbisProperties.class);
    }

    private ApplicationContextRunner collectionConfigRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(
                org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(CollectionConfig.class);
    }

    private static List<String> aliasesOf(
        GbisProperties properties
    ) {
        return properties.serviceKeys().stream().map(GbisKey::alias).toList();
    }

    private static String serviceKeyOf(
        GbisProperties properties,
        String alias
    ) {
        return properties.serviceKeys().stream()
            .filter(key -> key.alias().equals(alias))
            .map(GbisKey::serviceKey)
            .findFirst()
            .orElseThrow();
    }

    private Throwable catchThrowableOf(
        String serviceKey
    ) {
        try {
            new GbisProperties(BASE_URL, serviceKey, DAILY_LIMIT);
            throw new AssertionError("예외가 안 났다");
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 메시지뿐 아니라 원인 예외까지 훑는다. 원인에 인증키가 실려 오는 것이 이 테스트가 막는 것이다. */
    private String messagesOf(
        Throwable thrown
    ) {
        StringBuilder joined = new StringBuilder();
        for (Throwable each = thrown; each != null; each = each.getCause()) {
            joined.append(each.getMessage()).append(' ').append(each.getClass().getName()).append(' ');
        }
        return joined.toString();
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(GbisProperties.class)
    static class EnableGbisProperties {
    }
}
