package com.gustler.backend.archive;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 날짜 파티션 내보내기와 옛 테이블 내보내기가 S3 에 남긴 파일만으로 줄을 빈 테이블에 되살린다.
 *
 * <p>manifest 를 읽어 해시를 확인하고, manifest 의 열 이름·타입으로 새 테이블을 만든 뒤, 파일마다 줄을
 * {@code jsonb_populate_record} 로 넣는다. 원본 DB 의 테이블 정의는 읽지 않는다. 운영의 "S3 파일만으로 되살릴 수 있다"
 * 확인과 같은 절차다.
 */
final class PartitionRestore {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9 _]*(\\(\\d+(,\\d+)?\\))?(\\[\\])?");

    private PartitionRestore() {
    }

    /**
     * @param manifestKey 장부 {@code object_key}
     * @param manifestSha 장부 {@code sha256}
     * @param target 새로 만들 테이블 이름. 이미 있으면 실패한다.
     * @return 되살린 줄 수
     */
    static long restore(
        JdbcClient jdbc,
        ArchiveObjects objects,
        String manifestKey,
        String manifestSha,
        String target
    ) {
        byte[] manifestBytes = objects.read(manifestKey, ArchiveFiles.MAX_BYTES);
        if (!ArchiveFiles.digest(manifestBytes).equals(manifestSha)) {
            throw new IllegalStateException("MANIFEST_HASH_MISMATCH");
        }
        PartitionManifest manifest = PartitionManifest.parse(manifestBytes);
        create(jdbc, target, manifest.columns());
        final long restored = insert(jdbc, objects, target, manifest.columns(), manifest.files());
        if (restored != manifest.rows()) {
            throw new IllegalStateException("RESTORED_ROW_COUNT_MISMATCH");
        }
        return restored;
    }

    /**
     * 옛 테이블 하나의 조각 manifest 들만으로 그 테이블을 되살린다. 조각 번호가 0부터 이어지고 각 조각의 {@code after} 가 앞 조각의
     * {@code keyTo} 와 같으며 열 정의가 모두 같은지 확인한다.
     *
     * @param segments 조각 번호 순서의 {키, SHA-256} 쌍. 장부 {@code object_key}·{@code sha256} 이다.
     * @param target 새로 만들 테이블 이름. 이미 있으면 실패한다.
     * @return 되살린 줄 수
     */
    static long restoreLegacy(
        JdbcClient jdbc,
        ArchiveObjects objects,
        String table,
        List<String[]> segments,
        String target
    ) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("되살릴 조각이 없다");
        }
        List<PartitionManifest.Column> columns = null;
        String previousKeyTo = null;
        long restored = 0;
        for (int number = 0; number < segments.size(); number++) {
            byte[] manifestBytes = objects.read(segments.get(number)[0], ArchiveFiles.MAX_BYTES);
            if (!ArchiveFiles.digest(manifestBytes).equals(segments.get(number)[1])) {
                throw new IllegalStateException("MANIFEST_HASH_MISMATCH");
            }
            LegacySegmentManifest manifest = LegacySegmentManifest.parse(manifestBytes);
            if (breaksChain(manifest, table, number, previousKeyTo)) {
                throw new IllegalStateException("SEGMENT_CHAIN_BROKEN");
            }
            if (columns != null && !columns.equals(manifest.columns())) {
                throw new IllegalStateException("SEGMENT_COLUMNS_CHANGED");
            }
            if (columns == null) {
                columns = manifest.columns();
                create(jdbc, target, columns);
            }
            final long rows = insert(jdbc, objects, target, columns, manifest.files());
            if (rows != manifest.rows()) {
                throw new IllegalStateException("RESTORED_ROW_COUNT_MISMATCH");
            }
            restored += rows;
            previousKeyTo = manifest.keyTo();
        }
        return restored;
    }

    private static boolean breaksChain(
        LegacySegmentManifest manifest,
        String table,
        final int number,
        String previousKeyTo
    ) {
        return !LegacySegmentManifest.FORMAT.equals(manifest.format()) || !table.equals(manifest.table())
            || manifest.segment() != number || !Objects.equals(manifest.after(), previousKeyTo);
    }

    private static void create(JdbcClient jdbc, String target, List<PartitionManifest.Column> columns) {
        require(IDENTIFIER, target);
        List<String> definitions = columns.stream()
            .map(c -> require(IDENTIFIER, c.name()) + " " + require(TYPE, c.type())).toList();
        jdbc.sql("CREATE TABLE " + target + " (" + String.join(", ", definitions) + ")").update();
    }

    /**
     * 파일의 줄을 넣는다. {@code jsonb} 열은 줄의 {@link LegacyExportRepository#JSONB_NULL} 표시에 이름이 있으면 SQL NULL 이 아니라
     * JSON {@code null} 로 넣는다.
     */
    private static long insert(
        JdbcClient jdbc,
        ArchiveObjects objects,
        String target,
        List<PartitionManifest.Column> columns,
        List<PartitionManifest.FileEntry> files
    ) {
        String select = String.join(", ", columns.stream().map(PartitionRestore::selectColumn).toList());
        long restored = 0;
        for (PartitionManifest.FileEntry file : files) {
            byte[] bytes = objects.read(file.key(), file.bytes());
            if (!ArchiveFiles.digest(bytes).equals(file.sha256())) {
                throw new IllegalStateException("FILE_HASH_MISMATCH");
            }
            List<String> lines = ArchiveFiles.decode(bytes);
            restored += jdbc.sql("INSERT INTO " + target + " SELECT " + select
                + " FROM jsonb_array_elements(CAST(:rows AS jsonb)) r CROSS JOIN LATERAL jsonb_populate_record(NULL::"
                + target + ", r) p").param("rows", "[" + String.join(",", lines) + "]").update();
        }
        return restored;
    }

    private static String selectColumn(PartitionManifest.Column column) {
        if (column.type().equals("jsonb")) {
            return "CASE WHEN jsonb_exists(r->'" + LegacyExportRepository.JSONB_NULL + "', '" + column.name()
                + "') THEN CAST('null' AS jsonb) ELSE p." + column.name() + " END";
        }
        return "p." + column.name();
    }

    private static String require(Pattern pattern, String value) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(value);
        }
        return value;
    }
}
