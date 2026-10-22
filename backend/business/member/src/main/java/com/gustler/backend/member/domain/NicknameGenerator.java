package com.gustler.backend.member.domain;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 가입할 때 쓰는 무작위 닉네임을 만든다.
 *
 * <p>형식은 형용사, 정류장 이름, 숫자를 공백 없이 이은 것이다. 정류장은 형용사와 숫자를 붙여도 닉네임 길이 상한을 넘지 않는
 * 것 중에서 고른다. 숫자는 요청한 자리 수를 정확히 채우고 0으로 시작하지 않는다. 이미 쓰이는 닉네임인지는 알지 못한다.
 */
public final class NicknameGenerator {
    /** 채팅 닉네임과 같은 형용사. */
    static final List<String> ADJECTIVES = List.of(
        "졸린", "수다스러운", "용감한", "느긋한", "반짝이는", "신나는", "엉뚱한", "다정한",
        "배고픈", "명랑한", "부지런한", "설레는", "재빠른", "포근한", "궁금한", "씩씩한");

    /**
     * 실제 노선의 정류장 이름에서 골라 다듬은 목록이다. 상호명·단지·학교·교회·도로 시설·대학처럼 닉네임으로 어색한 이름은
     * 뺐다. 노선이 바뀌어도 고치지 않아도 된다.
     */
    static final List<String> STOPS = List.of(
        "판교역", "서현역", "야탑역", "이매역", "미금역", "오리역", "잠실역", "송파역", "석촌역", "장지역", "산본역",
        "안양역", "성남역", "삼가역", "강남대역",
        "명동", "시청", "숭례문", "해방촌", "죽전", "금곡", "상록", "북창동", "오야동", "신촌동", "수원동", "삼가동",
        "가락시장", "남부시장", "용인시장", "석촌호수", "중앙공원", "효자고개", "고인돌", "서울시청", "군포시청", "안양시청",
        "까치마을", "목련마을", "방죽마을", "쟁골마을", "상록마을", "상미마을", "동진마을", "내촌마을", "역말마을",
        "하얀마을", "교수마을");

    private static final String SALMON = "연어";
    private static final int LONGEST_ADJECTIVE = ADJECTIVES.stream().mapToInt(String::length).max().orElseThrow();
    private static final int SHORTEST_STOP = STOPS.stream().mapToInt(String::length).min().orElseThrow();
    private static final int DECIMAL_BASE = 10;

    private final RandomGenerator random;

    public NicknameGenerator(RandomGenerator random) {
        this.random = random;
    }

    /**
     * 형용사, 정류장 이름, {@code digits} 자리 숫자를 이은 닉네임.
     *
     * <p>{@code digits} 는 1 이상이고, 가장 긴 형용사와 가장 짧은 정류장 뒤에 붙여도 닉네임 길이 상한을 넘지 않아야 한다.
     */
    public Nickname withStop(final int digits) {
        requireDigits(digits, LONGEST_ADJECTIVE + SHORTEST_STOP);
        String adjective = pick(ADJECTIVES);
        List<String> fitting = STOPS.stream()
            .filter(stop -> adjective.length() + stop.length() + digits <= Nickname.MAX_LENGTH)
            .toList();
        return new Nickname(adjective + pick(fitting) + number(digits));
    }

    /** {@code 연어} 와 {@code digits} 자리 숫자를 이은 닉네임. {@code digits} 는 1 이상이고 길이 상한을 넘지 않아야 한다. */
    public Nickname salmonOnly(final int digits) {
        requireDigits(digits, SALMON.length());
        return new Nickname(SALMON + number(digits));
    }

    private String number(final int digits) {
        final int lowest = powerOfTen(digits - 1);
        return String.valueOf(lowest + random.nextInt(powerOfTen(digits) - lowest));
    }

    private String pick(List<String> words) {
        return words.get(random.nextInt(words.size()));
    }

    private static void requireDigits(final int digits, final int prefixLength) {
        if (digits < 1 || prefixLength + digits > Nickname.MAX_LENGTH) {
            throw new IllegalArgumentException("닉네임 숫자 자리 수가 허용 범위를 벗어났다: " + digits);
        }
    }

    private static int powerOfTen(final int exponent) {
        int value = 1;
        for (int step = 0; step < exponent; step++) {
            value *= DECIMAL_BASE;
        }
        return value;
    }
}
