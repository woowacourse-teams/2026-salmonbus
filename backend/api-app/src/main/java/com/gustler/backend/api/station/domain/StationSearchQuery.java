package com.gustler.backend.api.station.domain;

import com.gustler.backend.api.station.InvalidStationSearchException;

/**
 * 정류장 이름 검색어를 정규화한 검색 키.
 *
 * <p>키는 영문 소문자·숫자·한글만 담는다. DB의 station_search_key 함수와 같은 규칙이며, 규칙을 바꾸면 V86 마이그레이션의
 * name_key 열도 함께 다시 만들어야 한다. 키에는 LIKE 와일드카드(% _)가 들어갈 수 없으므로 패턴을 만들 때 이스케이프가 필요 없다.
 */
public final class StationSearchQuery {

    public static final int MAX_KEY_LENGTH = 40;

    private final String key;

    private StationSearchQuery(String key) {
        this.key = key;
    }

    public static StationSearchQuery parse(String raw) {
        if (raw == null) {
            throw new InvalidStationSearchException();
        }
        StringBuilder key = new StringBuilder();
        raw.codePoints().forEach(point -> {
            if (point >= '0' && point <= '9' || point >= 'a' && point <= 'z' || point >= '가' && point <= '힣') {
                key.appendCodePoint(point);
            } else if (point >= 'A' && point <= 'Z') {
                key.appendCodePoint(point + ('a' - 'A'));
            }
        });
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidStationSearchException();
        }
        return new StationSearchQuery(key.toString());
    }

    public String key() {
        return key;
    }
}
