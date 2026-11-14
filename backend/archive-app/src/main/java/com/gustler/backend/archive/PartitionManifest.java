package com.gustler.backend.archive;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * 날짜 파티션 하나를 내보낸 결과를 설명하는 manifest 다. S3 에는 이 기록을 JSON 으로 쓴 바이트가 올라가고, 그 SHA-256 이 장부
 * {@code daily_export.sha256} 이다.
 *
 * <p>파일은 {@code number} 순서로 이어 붙이면 기본 키 순서의 전체 줄이 된다. 한 줄은 {@code to_jsonb(row)::text} 원문이다.
 * {@code columns} 는 부모 테이블의 열 순서와 {@code format_type} 표기라서 이 manifest 만으로 같은 모양의 빈 테이블을 만들 수 있다.
 *
 * @param format 형식 이름. 언제나 {@value #FORMAT}.
 * @param table 부모 테이블 이름
 * @param sourceDate 파티션의 한국 날짜(ISO)
 * @param partition 내보낸 파티션 이름
 * @param rows 전체 줄 수
 * @param orderBy 파일 안 줄의 정렬 열(기본 키 순서)
 * @param columns 열 이름과 타입
 * @param exportedAt 내보내기를 마친 시각(ISO, UTC)
 * @param files 파일 목록
 */
public record PartitionManifest(
    String format,
    String table,
    String sourceDate,
    String partition,
    long rows,
    List<String> orderBy,
    List<Column> columns,
    String exportedAt,
    List<FileEntry> files
) {
    public static final String FORMAT = "salmonbus-partition-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public PartitionManifest {
        orderBy = List.copyOf(orderBy);
        columns = List.copyOf(columns);
        files = List.copyOf(files);
    }

    /** 열 하나. {@code type} 은 {@code format_type(atttypid, atttypmod)} 결과다. */
    public record Column(String name, String type) {
    }

    /** 파일 하나. {@code sha256} 은 gzip 바이트의 SHA-256 16진 표기다. */
    public record FileEntry(int number, String key, String sha256, int rows, int bytes) {
    }

    public byte[] toBytes() {
        return JSON.writeValueAsBytes(this);
    }

    public static PartitionManifest parse(byte[] bytes) {
        return JSON.readValue(bytes, PartitionManifest.class);
    }

    /** 내보낸 시각을 빼고 같은 내용인지 본다. 같은 파티션을 두 번 내보내 manifest 만 다른 경우를 가린다. */
    boolean sameContent(PartitionManifest other) {
        return format.equals(other.format) && table.equals(other.table) && sourceDate.equals(other.sourceDate)
            && rows == other.rows && orderBy.equals(other.orderBy) && columns.equals(other.columns)
            && files.equals(other.files);
    }
}
