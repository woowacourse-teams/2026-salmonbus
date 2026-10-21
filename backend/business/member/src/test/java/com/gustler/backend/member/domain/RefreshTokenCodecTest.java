package com.gustler.backend.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RefreshTokenCodecTest {
    private static final UUID FAMILY_ID = UUID.fromString("0b7e1c3a-5d2f-4a86-9e41-7c3d2b9f6a10");
    private static final String ALPHABET = "ABCxyz019_-.";

    private final RefreshTokenCodec codec = new RefreshTokenCodec(secret("codec-test-only-secret-key-00000001"));

    @Test
    void 토큰은_계통_세대_서명을_점으로_이은_형식이다() {
        // when
        String actual = codec.encode(FAMILY_ID, 7);

        // then
        assertThat(actual).matches("[A-Za-z0-9_-]{22}\\.7\\.[A-Za-z0-9_-]{43}");
    }

    @Test
    void 만든_토큰을_풀면_계통과_세대가_그대로_나온다() {
        // given
        String token = codec.encode(FAMILY_ID, 12);

        // when
        Optional<RefreshTokenClaim> actual = codec.decode(token);

        // then
        assertThat(actual).contains(new RefreshTokenClaim(FAMILY_ID, 12));
    }

    @Test
    void 같은_계통과_세대는_언제나_같은_토큰이고_세대나_계통이_다르면_다른_토큰이다() {
        // when
        String first = codec.encode(FAMILY_ID, 3);
        String again = codec.encode(FAMILY_ID, 3);

        // then
        assertSoftly(softly -> {
            softly.assertThat(again).isEqualTo(first);
            softly.assertThat(codec.encode(FAMILY_ID, 4)).isNotEqualTo(first);
            softly.assertThat(codec.encode(UUID.randomUUID(), 3)).isNotEqualTo(first);
        });
    }

    @Test
    void 세대를_바꾼_토큰은_거절한다() {
        // given
        String[] parts = codec.encode(FAMILY_ID, 3).split("\\.");

        // when
        Optional<RefreshTokenClaim> actual = codec.decode(parts[0] + ".4." + parts[2]);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 계통을_바꾼_토큰은_거절한다() {
        // given
        String[] other = codec.encode(UUID.randomUUID(), 3).split("\\.");
        String[] parts = codec.encode(FAMILY_ID, 3).split("\\.");

        // when
        Optional<RefreshTokenClaim> actual = codec.decode(other[0] + "." + parts[1] + "." + parts[2]);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 서명을_바꾼_토큰은_거절한다() {
        // given
        String token = codec.encode(FAMILY_ID, 3);
        char last = token.charAt(token.length() - 1);
        char replaced = 'A';
        if (last == 'A') {
            replaced = 'B';
        }

        // when
        Optional<RefreshTokenClaim> actual = codec.decode(token.substring(0, token.length() - 1) + replaced);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 다른_비밀로_만든_토큰은_거절한다() {
        // given
        RefreshTokenCodec other = new RefreshTokenCodec(secret("another-test-only-secret-key-0000002"));

        // when
        Optional<RefreshTokenClaim> actual = codec.decode(other.encode(FAMILY_ID, 3));

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 세대_앞에_0을_붙인_토큰은_거절한다() {
        // given
        String[] parts = codec.encode(FAMILY_ID, 3).split("\\.");

        // when
        Optional<RefreshTokenClaim> actual = codec.decode(parts[0] + ".03." + parts[2]);

        // then
        assertThat(actual).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("malformedTokens")
    void 형식이_깨진_입력은_예외_없이_거절한다(String token) {
        // when
        Optional<RefreshTokenClaim> actual = codec.decode(token);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void null_과_아주_긴_입력도_예외_없이_거절한다() {
        // given
        String veryLong = "A".repeat(1_000_000);

        // when
        Optional<RefreshTokenClaim> nullToken = codec.decode(null);
        Optional<RefreshTokenClaim> longToken = codec.decode(veryLong);

        // then
        assertSoftly(softly -> {
            softly.assertThat(nullToken).isEmpty();
            softly.assertThat(longToken).isEmpty();
        });
    }

    @Test
    void 무작위_문자열을_풀어도_예외가_나지_않고_모두_거절한다() {
        // given
        Random random = new Random(20261021);

        // when
        long accepted = IntStream.range(0, 20_000)
            .mapToObj(index -> randomToken(random))
            .filter(token -> codec.decode(token).isPresent())
            .count();

        // then
        assertThat(accepted).isZero();
    }

    @Test
    void 문자열로_바꿔도_비밀이_드러나지_않는다() {
        // when
        String actual = codec.toString();

        // then
        assertThat(actual).doesNotContain("codec-test-only");
    }

    static Stream<String> malformedTokens() {
        String family = "A".repeat(22);
        String mac = "A".repeat(43);
        return Stream.of(
            "", " ", "abc", "a.b", "a.b.c.d", "..", family + "." + mac, family + ".1." + "A".repeat(42),
            family + ".x." + mac, family + ".-1." + mac, family + "." + mac + ".1", family + ".99999999999." + mac,
            family + ".2147483648." + mac, family + ".1." + mac, "!".repeat(22) + ".1." + mac);
    }

    private static String randomToken(Random random) {
        StringBuilder builder = new StringBuilder();
        int length = random.nextInt(80);
        for (int index = 0; index < length; index++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }

    private static byte[] secret(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
