package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService.Archived;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore.Location;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore.Snapshot;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStorage.Verification;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PurgeArchivedEvaluationsServiceTest {
    private static final UUID BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000176");
    private static final UUID TOKEN = UUID.fromString("00000000-0000-0000-0000-000000000177");
    private static final Instant LEASE = Instant.parse("2026-10-27T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);
    private final EvaluationArchiveStore archives = mock(EvaluationArchiveStore.class);
    private final EvaluationArchiveStorage storage = mock(EvaluationArchiveStorage.class);
    private final EvaluationArchiveRetentionStore retention = mock(EvaluationArchiveRetentionStore.class);
    private final EvaluationArchiveSource source = mock(EvaluationArchiveSource.class);
    private final EvaluationArchiveBatch reserved = new EvaluationArchiveBatch(BATCH_ID, 3, 1, TOKEN, LEASE,
        State.RESERVED, 1, null);
    private final EvaluationArchiveBatch verified = new EvaluationArchiveBatch(BATCH_ID, 3, 1, TOKEN, LEASE,
        State.VERIFIED, 1, DIGEST);
    private final List<Row> rows = List.of(new Row(new Key(7, 9), "original", "b".repeat(64)));
    private final Snapshot snapshot = new Snapshot(verified, 0, 1, Location.LIVE);

    @Test
    void 같은_실행은_업로드한_원본을_DB_삭제_검증으로_전달하고_S3를_읽지_않는다() {
        // given
        ArrayList<Row> original = new ArrayList<>(rows);
        when(archives.readOwned(reserved)).thenReturn(original);
        when(storage.storeAndVerify(reserved, rows)).thenReturn(new Verification("test-only", DIGEST));
        when(retention.preparePurge(reserved)).thenReturn(snapshot);
        when(retention.purge(snapshot, rows)).thenReturn(1);

        // when
        Archived archived = new ArchiveCompletedEvaluationsService(archives, storage).archive(reserved);
        original.clear();
        final int removed = new PurgeArchivedEvaluationsService(retention, source).purge(reserved, archived);

        // then
        assertThat(removed).isOne();
        assertThat(archived.batch()).isEqualTo(verified);
        assertThat(archived.rows()).isEqualTo(rows);
        var order = inOrder(archives, storage, retention);
        order.verify(archives).readOwned(reserved);
        order.verify(storage).storeAndVerify(reserved, rows);
        order.verify(archives).recordVerified(reserved, DIGEST);
        order.verify(retention).preparePurge(reserved);
        order.verify(retention).purge(snapshot, rows);
        verifyNoInteractions(source);
    }

    @Test
    void 메모리에_업로드_원본이_없는_재개는_S3를_읽고_DB_삭제_검증으로_전달한다() {
        // given
        when(retention.preparePurge(verified)).thenReturn(snapshot);
        when(source.readOriginal(verified)).thenReturn(rows);
        when(retention.purge(snapshot, rows)).thenReturn(1);

        // when
        final int removed = new PurgeArchivedEvaluationsService(retention, source).purge(verified);

        // then
        assertThat(removed).isOne();
        var order = inOrder(retention, source);
        order.verify(retention).preparePurge(verified);
        order.verify(source).readOriginal(verified);
        order.verify(retention).purge(snapshot, rows);
    }

    @ParameterizedTest
    @MethodSource("changedBatches")
    void 저장한_원본의_묶음이나_검증_상태가_다르면_삭제하지_않는다(EvaluationArchiveBatch changed) {
        // given
        when(retention.preparePurge(reserved)).thenReturn(new Snapshot(changed, 0, changed.qualityRevision(),
            Location.LIVE));
        Archived archived = new Archived(verified, rows);

        // when, then
        assertThatThrownBy(() -> new PurgeArchivedEvaluationsService(retention, source).purge(reserved, archived))
            .isInstanceOf(IllegalStateException.class);
        verify(retention, never()).purge(any(), any());
        verifyNoInteractions(source);
    }

    private static Stream<EvaluationArchiveBatch> changedBatches() {
        return Stream.of(
            new EvaluationArchiveBatch(UUID.randomUUID(), 3, 1, TOKEN, LEASE, State.VERIFIED, 1, DIGEST),
            new EvaluationArchiveBatch(BATCH_ID, 4, 1, TOKEN, LEASE, State.VERIFIED, 1, DIGEST),
            new EvaluationArchiveBatch(BATCH_ID, 3, 2, TOKEN, LEASE, State.VERIFIED, 1, DIGEST),
            new EvaluationArchiveBatch(BATCH_ID, 3, 1, UUID.randomUUID(), LEASE, State.VERIFIED, 1, DIGEST),
            new EvaluationArchiveBatch(BATCH_ID, 3, 1, TOKEN, LEASE, State.VERIFIED, 2, DIGEST),
            new EvaluationArchiveBatch(BATCH_ID, 3, 1, TOKEN, LEASE, State.VERIFIED, 1, "c".repeat(64)));
    }
}
