package com.gustler.backend.archive;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 날짜 파티션 내보내기가 S3 에 남긴 파일만으로 그 날짜의 줄을 빈 테이블에 되살린다.
 *
 * <p>manifest 를 읽어 해시를 확인하고, manifest 의 열 이름·타입으로 새 테이블을 만든 뒤, 파일마다 줄을
 * {@code jsonb_populate_recordset} 으로 넣는다. 원본 DB 의 테이블 정의나 장부는 읽지 않는다. 운영의 "S3 파일만으로 되살릴 수 있다"
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
    static long restore(JdbcClient jdbc, ArchiveObjects objects, String manifestKey, String manifestSha,
        String target) {
        byte[] manifestBytes = objects.read(manifestKey, ArchiveFiles.MAX_BYTES);
        if (!ArchiveFiles.digest(manifestBytes).equals(manifestSha)) {
            throw new IllegalStateException("MANIFEST_HASH_MISMATCH");
        }
        PartitionManifest manifest = PartitionManifest.parse(manifestBytes);
        require(IDENTIFIER, target);
        List<String> columns = manifest.columns().stream()
            .map(c -> require(IDENTIFIER, c.name()) + " " + require(TYPE, c.type())).toList();
        jdbc.sql("CREATE TABLE " + target + " (" + String.join(", ", columns) + ")").update();
        long restored = 0;
        for (PartitionManifest.FileEntry file : manifest.files()) {
            byte[] bytes = objects.read(file.key(), file.bytes());
            if (!ArchiveFiles.digest(bytes).equals(file.sha256())) {
                throw new IllegalStateException("FILE_HASH_MISMATCH");
            }
            List<String> lines = ArchiveFiles.decode(bytes);
            restored += jdbc.sql("INSERT INTO " + target + " SELECT * FROM jsonb_populate_recordset(NULL::" + target
                + ", CAST(:rows AS jsonb))").param("rows", "[" + String.join(",", lines) + "]").update();
        }
        if (restored != manifest.rows()) {
            throw new IllegalStateException("RESTORED_ROW_COUNT_MISMATCH");
        }
        return restored;
    }

    private static String require(Pattern pattern, String value) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(value);
        }
        return value;
    }
}
