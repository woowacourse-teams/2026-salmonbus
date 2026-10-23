package com.gustler.backend.member.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NicknameGeneratorTest {
    private static final Pattern FORMAT = Pattern.compile("^(" + String.join("|", NicknameGenerator.ADJECTIVES) + ")("
        + String.join("|", NicknameGenerator.STOPS) + ")([1-9][0-9]*)$");

    @Test
    void 정류장_목록은_한글_두_자에서_네_자이고_닉네임_규칙을_지키며_겹치지_않는다() {
        // when & then
        assertSoftly(softly -> {
            softly.assertThat(NicknameGenerator.STOPS).doesNotHaveDuplicates();
            softly.assertThat(NicknameGenerator.STOPS).allSatisfy(stop -> {
                assertThat(stop).matches("[가-힣]{2,4}");
                assertThat(Nickname.violationOf(stop)).isEmpty();
            });
            softly.assertThat(NicknameGenerator.ADJECTIVES).hasSize(16).doesNotHaveDuplicates();
        });
    }

    @Test
    void 어느_형용사에도_숫자_세_자리까지_붙일_수_있는_정류장이_있다() {
        // when & then
        assertThat(NicknameGenerator.ADJECTIVES).allSatisfy(adjective -> assertThat(NicknameGenerator.STOPS)
            .anyMatch(stop -> adjective.length() + stop.length() + 3 <= Nickname.MAX_LENGTH));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void 무작위로_만든_닉네임은_목록의_형용사와_정류장과_요청한_자리_수의_숫자로_이뤄진다(final int digits) {
        // given
        NicknameGenerator generator = new NicknameGenerator(new Random(20261022L + digits));

        // when
        List<String> actual = IntStream.range(0, 1000).mapToObj(index -> generator.withStop(digits).value()).toList();

        // then
        assertThat(actual).allSatisfy(nickname -> {
            Matcher matcher = FORMAT.matcher(nickname);
            assertSoftly(softly -> {
                softly.assertThat(matcher.matches()).as(nickname).isTrue();
                softly.assertThat(matcher.group(3)).hasSize(digits);
                softly.assertThat(nickname.length()).isLessThanOrEqualTo(Nickname.MAX_LENGTH);
                softly.assertThat(Nickname.violationOf(nickname)).isEmpty();
            });
        });
    }

    @Test
    void 가장_앞의_후보를_고르면_첫_형용사와_첫_정류장과_가장_작은_숫자다() {
        // given
        NicknameGenerator generator = new NicknameGenerator(EdgeRandom.first());

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(generator.withStop(1).value()).isEqualTo("졸린판교역1");
            softly.assertThat(generator.withStop(2).value()).isEqualTo("졸린판교역10");
            softly.assertThat(generator.withStop(3).value()).isEqualTo("졸린판교역100");
            softly.assertThat(generator.salmonOnly(8).value()).isEqualTo("연어10000000");
        });
    }

    @Test
    void 가장_뒤의_후보를_고르면_마지막_형용사와_마지막_정류장과_가장_큰_숫자다() {
        // given
        NicknameGenerator generator = new NicknameGenerator(EdgeRandom.last());

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(generator.withStop(2).value()).isEqualTo("씩씩한교수마을99");
            softly.assertThat(generator.withStop(3).value()).isEqualTo("씩씩한교수마을999");
            softly.assertThat(generator.salmonOnly(8).value()).isEqualTo("연어99999999");
        });
    }

    @Test
    void 형용사와_숫자를_붙이면_길이_상한을_넘는_정류장은_고르지_않는다() {
        // given
        NicknameGenerator generator = new NicknameGenerator(new ScriptedRandom(1, 4, 0));

        // when
        Nickname actual = generator.withStop(3);

        // then
        assertThat(actual.value()).as("수다스러운 뒤에는 명동·시청·죽전·금곡·상록만 남는다").isEqualTo("수다스러운상록100");
    }

    @Test
    void 같은_난수_씨앗이면_같은_닉네임을_만든다() {
        // given
        NicknameGenerator first = new NicknameGenerator(new Random(7));
        NicknameGenerator second = new NicknameGenerator(new Random(7));

        // when
        List<String> firstNicknames = IntStream.range(0, 20).mapToObj(index -> first.withStop(2).value()).toList();
        List<String> secondNicknames = IntStream.range(0, 20).mapToObj(index -> second.withStop(2).value()).toList();

        // then
        assertThat(firstNicknames).isEqualTo(secondNicknames);
    }

    @Test
    void 숫자_자리_수가_허용_범위를_벗어나면_만들지_않는다() {
        // given
        NicknameGenerator generator = new NicknameGenerator(EdgeRandom.first());

        // when & then
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> generator.withStop(0)).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> generator.withStop(4)).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> generator.salmonOnly(0)).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> generator.salmonOnly(9)).isInstanceOf(IllegalArgumentException.class);
        });
    }

    /** 범위의 맨 앞이나 맨 뒤만 고르는 난수. */
    private static final class EdgeRandom implements RandomGenerator {
        private final boolean last;

        private EdgeRandom(final boolean last) {
            this.last = last;
        }

        static EdgeRandom first() {
            return new EdgeRandom(false);
        }

        static EdgeRandom last() {
            return new EdgeRandom(true);
        }

        @Override
        public int nextInt(final int bound) {
            if (last) {
                return bound - 1;
            }
            return 0;
        }

        @Override
        public long nextLong() {
            return 0;
        }
    }

    /** 정해 둔 값을 차례로 돌려주는 난수. 값이 범위를 벗어나면 그 자리에서 실패한다. */
    private static final class ScriptedRandom implements RandomGenerator {
        private final Deque<Integer> values = new ArrayDeque<>();

        private ScriptedRandom(int... values) {
            IntStream.of(values).forEach(this.values::add);
        }

        @Override
        public int nextInt(final int bound) {
            final int value = values.removeFirst();
            assertThat(value).isLessThan(bound);
            return value;
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException();
        }
    }
}
