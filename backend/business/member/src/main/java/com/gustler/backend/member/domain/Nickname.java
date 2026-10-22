package com.gustler.backend.member.domain;

import com.gustler.backend.member.api.NicknameViolation;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 회원이 서비스에서 쓰는 이름.
 *
 * <p>앞뒤 공백을 제거하고 NFC 로 정규화한 값이 닉네임이다. 길이는 2~10자이며 한글 완성형·영문·숫자만 쓴다.
 * 글자 수는 코드포인트로 센다. 문자 규칙은 member 테이블의 {@code ck_member_nickname} 과 같고,
 * 금칙어는 DB 가 아니라 이 클래스만 검사한다.
 *
 * <p>중복 여부는 이 클래스가 알 수 없다. 영문 대소문자를 구분하지 않는 고유성은 저장소가 판정한다.
 */
public record Nickname(String value) {
    private static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 10;
    private static final Pattern ALLOWED = Pattern.compile("[가-힣A-Za-z0-9]+");
    private static final List<String> RESERVED = List.of("관리자", "운영자", "연어버스", "salmonbus", "admin");

    public Nickname {
        value = normalize(value);
        Optional<NicknameViolation> violation = violationOf(value);
        if (violation.isPresent()) {
            throw new IllegalArgumentException("닉네임 규칙을 어겼다: " + violation.get());
        }
    }

    /** 규칙에 맞는 닉네임이면 돌려주고, 아니면 비어 있다. {@code null} 도 규칙 위반으로 본다. */
    public static Optional<Nickname> of(String raw) {
        if (violationOf(raw).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new Nickname(raw));
    }

    /** 정규화한 뒤 규칙을 어긴 이유를 돌려준다. 어기지 않았으면 비어 있다. */
    public static Optional<NicknameViolation> violationOf(String raw) {
        if (raw == null) {
            return Optional.of(NicknameViolation.LENGTH);
        }
        String normalized = normalize(raw);
        final int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return Optional.of(NicknameViolation.LENGTH);
        }
        if (!ALLOWED.matcher(normalized).matches()) {
            return Optional.of(NicknameViolation.CHARACTERS);
        }
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (RESERVED.stream().anyMatch(lowered::contains)) {
            return Optional.of(NicknameViolation.RESERVED);
        }
        return Optional.empty();
    }

    private static String normalize(String raw) {
        return Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
    }
}
