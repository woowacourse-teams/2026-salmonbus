package com.gustler.backend.archive;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * 옛 테이블 하나를 기본 키 순서로 자른 조각 하나의 manifest 다. S3 에는 이 기록을 JSON 으로 쓴 바이트가 올라가고, 그 SHA-256 이
 * 장부 {@code daily_export.sha256} 이다.
 *
 * <p>조각 {@code segment} 는 기본 키가 {@code after} 보다 크고 {@code keyTo} 이하인 줄이다. {@code after} 는 앞 조각의
 * {@code keyTo} 이고 첫 조각은 {@code null} 이다. 조각 번호 순서로 파일을 이어 붙이면 테이블 전체가 기본 키 순서로 나온다.
 * 한 줄은 {@code to_jsonb(row)::text} 원문이고, 파일 형식은 날짜 파티션 내보내기({@link PartitionManifest})와 같다.
 *
 * @param format 형식 이름. 언제나 {@value #FORMAT}.
 * @param table 테이블 이름
 * @param segment 조각 번호(0부터)
 * @param after 이 조각이 시작하는 기본 키의 바로 앞(제외) 값. JSON 배열 텍스트이며 첫 조각은 {@code null}
 * @param keyFrom 첫 줄의 기본 키. JSON 배열 텍스트
 * @param keyTo 마지막 줄의 기본 키. JSON 배열 텍스트
 * @param rows 조각의 줄 수
 * @param orderBy 기본 키 열(정렬 순서)
 * @param columns 열 이름과 {@code format_type} 표기
 * @param exportedAt 조각을 마친 시각(ISO, UTC)
 * @param files 파일 목록
 */
public record LegacySegmentManifest(
    String format,
    String table,
    int segment,
    String after,
    String keyFrom,
    String keyTo,
    long rows,
    List<String> orderBy,
    List<PartitionManifest.Column> columns,
    String exportedAt,
    List<PartitionManifest.FileEntry> files
) {
    public static final String FORMAT = "salmonbus-legacy-segment-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public LegacySegmentManifest {
        orderBy = List.copyOf(orderBy);
        columns = List.copyOf(columns);
        files = List.copyOf(files);
    }

    public byte[] toBytes() {
        return JSON.writeValueAsBytes(this);
    }

    public static LegacySegmentManifest parse(byte[] bytes) {
        return JSON.readValue(bytes, LegacySegmentManifest.class);
    }
}
