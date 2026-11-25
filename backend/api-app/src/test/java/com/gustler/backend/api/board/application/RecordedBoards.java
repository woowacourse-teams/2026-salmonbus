package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.observation.ObservationFreshness;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 탑승판, 스냅샷, 일괄 읽기 결과를 실행마다 같은 글로 바꿔 기록해 둔 기대 응답과 견주게 한다.
 *
 * <p>DB가 매기는 식별자(노선 버전, 관측 묶음, 모델 배포)는 시험이 붙인 이름으로, 시각은 시험의 기준 시각에서 떨어진 만큼을
 * 고정 시각에 더한 값으로 바꾼다. 그 밖의 필드는 레코드의 문자열 표현을 그대로 쓰므로 필드 하나라도 다르면 글이 다르다.
 */
final class RecordedBoards {
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2026-01-01T12:00:00+09:00");
    private static final ZoneId SOURCE_DATE_ZONE = ZoneId.of("Asia/Seoul");
    /** 시험 픽스처는 관측 묶음의 예약 시각을 응답 수신 2초 전으로 둔다. */
    private static final Duration SCHEDULED_BEFORE_RECEIVED = Duration.ofSeconds(2);
    /** 기대 응답을 기록한 때의 조회 창이다. 심야가 아닌 시간의 값이다. */
    private static final Duration RECORDED_WINDOW = Duration.ofMinutes(5);
    private static final Pattern REVISION = Pattern.compile("c([^|]+)\\|n([^|]+)\\|m([^|]+)");
    private static final DateTimeFormatter DATABASE_TIME = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .appendOffset("+HH:mm", "+00")
        .toFormatter();

    private final OffsetDateTime now;
    private final Map<Long, String> versions = new HashMap<>();
    private final Map<Long, String> batches = new HashMap<>();
    private final Map<Long, String> models = new HashMap<>();

    RecordedBoards(OffsetDateTime now) {
        this.now = now;
    }

    /**
     * 시험 자원의 기대 응답을 읽는다. 한 행이 이름과 탭과 값이고, {@code #}으로 시작하는 줄과 빈 줄은 건너뛴다.
     */
    static Map<String, String> load(String resource) {
        try (InputStream input = RecordedBoards.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("기대 응답 자원이 없다: " + resource);
            }
            Map<String, String> recorded = new LinkedHashMap<>();
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            text.lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .forEach(line -> {
                    final int tab = line.indexOf('\t');
                    recorded.put(line.substring(0, tab), line.substring(tab + 1));
                });
            return recorded;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    RecordedBoards version(final long id, String name) {
        versions.put(id, name);
        return this;
    }

    RecordedBoards batch(final long id, String name) {
        batches.put(id, name);
        return this;
    }

    RecordedBoards model(final long id, String name) {
        models.put(id, name);
        return this;
    }

    String board(Board board) {
        BoardRoute route = board.route();
        BoardRoute namedRoute = new BoardRoute(
            route.id(),
            route.displayName(),
            route.startStopName(),
            route.endStopName(),
            route.status(),
            route.turnSequence(),
            route.directions(),
            versionName(route.referenceVersionId()),
            route.headways()
        );
        return new Board(
            namedRoute,
            shifted(board.observedAt()),
            recordedStaleAt(board),
            model(board.model()),
            board.vehiclesInService(),
            board.stops()
        ).toString();
    }

    /**
     * 만료 시각을 기록한 때의 조회 창으로 옮겨 기록한다. 조회 범위는 관측 시각이 심야인지에 따라 달라서 그대로 적으면 시험을 돌리는
     * 시간대에 따라 글이 달라진다. 만료 시각이 조회 창 규칙과 어긋난 만큼은 그대로 더해 기대 응답과 달라지게 한다.
     */
    private OffsetDateTime recordedStaleAt(Board board) {
        Duration offRule = Duration.between(ObservationFreshness.staleAt(board.observedAt()), board.staleAt());
        return shifted(board.observedAt()).plus(RECORDED_WINDOW).plus(offRule);
    }

    String snapshot(BoardSnapshot snapshot) {
        return "version=" + nameOf(versions, snapshot.routeVersionId())
            + " route=" + snapshot.route()
            + " turn=" + snapshot.turnSequence()
            + " schedule=" + snapshot.schedule()
            + " observation=" + snapshot.observation().map(this::observation).orElse("none")
            + " model=" + snapshot.activeModel().map(this::model).map(ForecastModel::toString).orElse("none")
            + " revision=" + revision(snapshot.revision());
    }

    String batchReads(Map<String, BoardBatchRead> reads) {
        StringBuilder text = new StringBuilder();
        reads.forEach((routeId, read) -> text.append('[').append(routeId).append(' ').append(batchRead(read))
            .append(']'));
        return text.toString();
    }

    static String failure(Throwable failure) {
        if (failure == null) {
            return "none";
        }
        return failure.getClass().getSimpleName();
    }

    private String batchRead(BoardBatchRead read) {
        if (read.skipped()) {
            return "skipped";
        }
        if (read.failure() != null) {
            return "failed " + failure(read.failure());
        }
        return "batch=" + nameOf(batches, read.reading().observationBatchId()) + " " + board(read.reading().board());
    }

    private String observation(SnapshotObservation observation) {
        return "batch=" + nameOf(batches, observation.batchId())
            + " observedAt=" + shifted(observation.observedAt())
            + " vehicles=" + observation.vehiclesInService()
            + " published=" + observation.forecastPublished()
            + " forecastDate=" + forecastDate(observation);
    }

    private static String forecastDate(SnapshotObservation observation) {
        LocalDate date = observation.forecastDate();
        if (date == null) {
            return "none";
        }
        LocalDate scheduledDate = observation.observedAt()
            .minus(SCHEDULED_BEFORE_RECEIVED)
            .atZoneSameInstant(SOURCE_DATE_ZONE)
            .toLocalDate();
        if (date.equals(scheduledDate)) {
            return "scheduled";
        }
        return "other";
    }

    private String revision(String revision) {
        if (revision == null) {
            return "null";
        }
        Matcher matcher = REVISION.matcher(revision);
        if (!matcher.matches()) {
            return revision;
        }
        return "c" + databaseTime(matcher.group(1))
            + "|n" + matcher.group(2)
            + "|m" + modelName(matcher.group(3));
    }

    private String databaseTime(String value) {
        if ("-".equals(value)) {
            return value;
        }
        return shifted(OffsetDateTime.parse(value, DATABASE_TIME)).toString();
    }

    private String modelName(String value) {
        if ("-".equals(value)) {
            return value;
        }
        return nameOf(models, Long.parseLong(value));
    }

    private ForecastModel model(ForecastModel model) {
        return new ForecastModel(
            0L,
            nameOf(models, model.deploymentId()) + "/" + model.releaseId(),
            shifted(model.trainedThrough())
        );
    }

    private String versionName(String referenceVersionId) {
        return nameOf(versions, Long.parseLong(referenceVersionId));
    }

    private static String nameOf(Map<Long, String> names, final long id) {
        return Optional.ofNullable(names.get(id)).orElse("unnamed");
    }

    private OffsetDateTime shifted(OffsetDateTime time) {
        return BASE.plus(Duration.between(now, time));
    }
}
