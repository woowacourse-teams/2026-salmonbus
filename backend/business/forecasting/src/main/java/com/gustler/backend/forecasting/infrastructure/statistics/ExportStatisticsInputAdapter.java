package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.api.statistics.ExportStatisticsInput;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 명시적으로 구성해서 호출하는 비교 입력 내보내기. 자동 스케줄이나 운영 기본 Bean이 아니다. */
public final class ExportStatisticsInputAdapter implements ExportStatisticsInput {
    private final JdbcStatisticsInputExtractor extractor;
    private final StatisticsExtractUploader uploader;
    private final Path workDirectory;
    private final long maxBytes;
    private final ZoneId zone;

    public ExportStatisticsInputAdapter(org.springframework.jdbc.core.simple.JdbcClient jdbc,
        org.springframework.transaction.PlatformTransactionManager transactions, Path workDirectory,
        long maxBytes, ZoneId zone) {
        if (workDirectory == null || !Files.isDirectory(workDirectory) || Files.isSymbolicLink(workDirectory)
            || maxBytes < 1 || maxBytes > 16 * 1024 * 1024 || zone == null) {
            throw new IllegalArgumentException("작업 디렉터리와 시간대, 16MiB 이하의 파일 한도가 필요하다");
        }
        this.extractor = new JdbcStatisticsInputExtractor(jdbc, transactions);
        this.uploader = new StatisticsExtractUploader();
        this.workDirectory = workDirectory;
        this.maxBytes = maxBytes;
        this.zone = zone;
    }

    @Override
    public synchronized String export(long routeVersionId, long afterObservationId, long throughObservationId,
        Instant dataUntil, int maxRows, UUID exportId) {
        if (exportId == null) {
            throw new IllegalArgumentException("추출 번호가 필요하다");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("내보내기는 기존 DB 트랜잭션 밖에서 실행해야 한다");
        }
        var extracted = extractor.extract(routeVersionId, afterObservationId, throughObservationId,
            dataUntil, zone, maxRows);
        if (extracted.rows().isEmpty()) {
            throw new IllegalArgumentException("복사할 완료 자료가 없다");
        }
        var bundle = StatisticsExtractWriter.write(workDirectory, extracted, maxBytes);
        String location = uploader.upload(bundle, exportId);
        new StatisticsExtractDownloader().download(workDirectory, exportId, bundle.manifest());
        return location;
    }
}
