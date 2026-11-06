package com.gustler.backend.api.boarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.gustler.backend.api.boarding.BoardingOptionsException;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Leg;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoardingOptionsQueryServiceTest {
    private static final String ROUTE = "204000057";
    private static final long VERSION = 7;
    private static final Set<StopRelation> ALL = EnumSet.allOf(StopRelation.class);
    private static final StopVisit ORIGIN = visit("205000231", 5, "UP", true);
    private static final StopVisit BEFORE = visit("205000222", 4, "UP", true);
    private static final StopVisit AFTER = visit("205000210", 9, "UP", true);
    private static final StopVisit ACROSS = visit("205000226", 81, "DOWN", true);
    private static final StopVisit NO_BOARDING = visit("277103149", 23, "UP", false);
    private final FakeRepository repository = new FakeRepository();
    private final FakeFinder walk = new FakeFinder();
    private final FakeFinder other = new FakeFinder();

    private static StopVisit visit(String station, final int sequence, String direction, final boolean boarding) {
        return new StopVisit(station, sequence, station + "-이름", direction, boarding, null, null);
    }

    private static Access walking(final int seconds) {
        return Access.of(List.of(new Leg(AccessMode.WALK, seconds, seconds, "TEST")));
    }

    private static BoardingOptionsQuery query(Set<StopRelation> relations, final int maxMinutes, final int limit,
        final boolean boardingOnly, Integer stopSequence) {
        return new BoardingOptionsQuery(new RouteId(ROUTE), ORIGIN.stationId(), stopSequence, null,
            Set.of(AccessMode.WALK), maxMinutes, limit, relations, boardingOnly);
    }

    private static BoardingOptionsQuery query() {
        return query(ALL, 15, 10, true, null);
    }

    private BoardingOptionsQueryService service(AccessFinder... finders) {
        return new BoardingOptionsQueryService(repository, List.of(finders));
    }

    private static List<String> stations(BoardingOptions result) {
        return result.options().stream().map(option -> option.stop().stationId()).toList();
    }

    @Test
    void 계산기마다_받은_이동_방법을_정류장별로_합치고_빠른_순서로_정렬한다() {
        // given
        repository.visits.addAll(List.of(BEFORE, ORIGIN, AFTER, ACROSS));
        walk.accesses.putAll(Map.of(BEFORE, List.of(walking(400)), ACROSS, List.of(walking(300))));
        other.accesses.putAll(Map.of(BEFORE, List.of(walking(120)), AFTER, List.of(walking(500))));

        // when
        BoardingOptions result = service(walk, other).get(query());

        // then
        assertThat(stations(result)).containsExactly("205000222", "205000226", "205000210");
        assertThat(result.options().getFirst().accesses()).extracting(Access::durationSeconds).containsExactly(120, 400);
        assertThat(result.options()).extracting(BoardingOptions.Option::relation)
            .containsExactly(StopRelation.BEFORE, StopRelation.OPPOSITE, StopRelation.AFTER);
        assertThat(result.options()).extracting(BoardingOptions.Option::sequenceOffset).containsExactly(-1, 76, 4);
        assertThat(result.referenceVersionId()).isEqualTo(VERSION);
    }

    @Test
    void 상한을_넘는_이동_방법은_빼고_남은_방법이_없으면_후보도_뺀다() {
        // given
        repository.visits.addAll(List.of(BEFORE, ORIGIN, AFTER));
        walk.accesses.putAll(Map.of(BEFORE, List.of(walking(400), walking(421)), AFTER, List.of(walking(421))));

        // when
        BoardingOptions result = service(walk).get(query(ALL, 7, 10, true, null));

        // then
        assertThat(walk.constraints.maxSeconds()).isEqualTo(420);
        assertThat(result.options()).singleElement().satisfies(option -> {
            assertThat(option.stop().stationId()).isEqualTo("205000222");
            assertThat(option.accesses()).extracting(Access::durationSeconds).containsExactly(400);
        });
    }

    @Test
    void 개수_제한은_이동_방법이_아니라_정류장_수다() {
        // given
        repository.visits.addAll(List.of(BEFORE, ORIGIN, AFTER));
        walk.accesses.putAll(Map.of(BEFORE, List.of(walking(100), walking(200), walking(300)),
            AFTER, List.of(walking(150))));

        // when
        BoardingOptions result = service(walk).get(query(ALL, 15, 1, true, null));

        // then
        assertThat(result.options()).singleElement()
            .satisfies(option -> assertThat(option.accesses()).hasSize(3));
    }

    @Test
    void 관계와_승차_가능_여부로_거른_방문만_계산기에_넘긴다() {
        // given
        repository.visits.addAll(List.of(BEFORE, ORIGIN, AFTER, ACROSS, NO_BOARDING));

        // when
        service(walk).get(query(Set.of(StopRelation.BEFORE, StopRelation.OPPOSITE), 15, 10, true, null));
        List<StopVisit> filtered = List.copyOf(walk.targets);
        service(walk).get(query(ALL, 15, 10, false, null));

        // then
        assertThat(filtered).containsExactly(BEFORE, ACROSS);
        assertThat(walk.origin).isEqualTo(ORIGIN);
        assertThat(walk.targets).containsExactly(BEFORE, AFTER, ACROSS, NO_BOARDING);
    }

    @Test
    void 방향이_다르면_순번이_앞서도_맞은편이다() {
        // given
        StopVisit earlierAcross = visit("205000230", 3, "DOWN", true);
        repository.visits.addAll(List.of(earlierAcross, BEFORE, ORIGIN));

        // when
        service(walk).get(query(Set.of(StopRelation.BEFORE), 15, 10, true, null));
        List<StopVisit> before = List.copyOf(walk.targets);
        service(walk).get(query(Set.of(StopRelation.OPPOSITE), 15, 10, true, null));

        // then
        assertThat(before).containsExactly(BEFORE);
        assertThat(walk.targets).containsExactly(earlierAcross);
    }

    @Test
    void 같은_정류장의_다른_방문은_SAME_STATION이다() {
        // given
        StopVisit again = visit(ORIGIN.stationId(), 60, "DOWN", true);
        repository.visits.addAll(List.of(ORIGIN, again));
        walk.accesses.put(again, List.of(walking(0)));

        // when
        BoardingOptions result = service(walk).get(query(ALL, 15, 10, true, 5));

        // then
        assertThat(result.options()).singleElement()
            .satisfies(option -> assertThat(option.relation()).isEqualTo(StopRelation.SAME_STATION));
    }

    @Test
    void 요청한_수단을_쓰는_계산기가_없으면_UNSUPPORTED_MODE다() {
        // given
        repository.visits.add(ORIGIN);

        // when
        BoardingOptionsException failure = catchThrowableOfType(BoardingOptionsException.class,
            () -> service().get(query()));

        // then
        assertThat(failure.code()).isEqualTo(ErrorCode.UNSUPPORTED_MODE);
    }

    @Test
    void 반복_방문인데_순번이_없으면_고를_수_없다() {
        // given
        repository.visits.addAll(List.of(ORIGIN, visit(ORIGIN.stationId(), 60, "DOWN", true)));

        // when
        BoardingOptionsException failure = catchThrowableOfType(BoardingOptionsException.class,
            () -> service(walk).get(query()));

        // then
        assertThat(failure.code()).isEqualTo(ErrorCode.STOP_VISIT_AMBIGUOUS);
    }

    @Test
    void 기준_정류장과_판본과_순번이_맞지_않으면_서로_다른_오류다() {
        // given
        repository.visits.add(ORIGIN);

        // when
        BoardingOptionsException absent = catchThrowableOfType(BoardingOptionsException.class,
            () -> service(walk).get(new BoardingOptionsQuery(new RouteId(ROUTE), "999999999", null, null,
                Set.of(AccessMode.WALK), 15, 10, ALL, true)));
        BoardingOptionsException staleVersion = catchThrowableOfType(BoardingOptionsException.class,
            () -> service(walk).get(new BoardingOptionsQuery(new RouteId(ROUTE), ORIGIN.stationId(), null, 6L,
                Set.of(AccessMode.WALK), 15, 10, ALL, true)));
        BoardingOptionsException wrongSequence = catchThrowableOfType(BoardingOptionsException.class,
            () -> service(walk).get(query(ALL, 15, 10, true, 6)));

        // then
        assertThat(absent.code()).isEqualTo(ErrorCode.STATION_NOT_FOUND);
        assertThat(staleVersion.code()).isEqualTo(ErrorCode.REFERENCE_VERSION_CONFLICT);
        assertThat(wrongSequence.code()).isEqualTo(ErrorCode.REFERENCE_VERSION_CONFLICT);
    }

    @Test
    void 카탈로그에_없는_노선은_404다() {
        // given
        repository.version = Optional.empty();

        // when
        Throwable failure = catchThrowableOfType(RouteNotFoundException.class, () -> service(walk).get(query()));

        // then
        assertThat(failure).isNotNull();
    }

    @Test
    void 범위를_벗어난_조건은_400이다() {
        // when
        BoardingOptionsException tooLong = catchThrowableOfType(BoardingOptionsException.class,
            () -> query(ALL, 31, 10, true, null));
        BoardingOptionsException tooMany = catchThrowableOfType(BoardingOptionsException.class,
            () -> query(ALL, 15, 51, true, null));
        BoardingOptionsException noRelation = catchThrowableOfType(BoardingOptionsException.class,
            () -> query(Set.of(), 15, 10, true, null));

        // then
        assertThat(tooLong.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(tooMany.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(noRelation.code()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private static final class FakeRepository implements BoardingOptionsQueryRepository {
        private final List<StopVisit> visits = new ArrayList<>();
        private Optional<Long> version = Optional.of(VERSION);

        @Override
        public Optional<Long> currentVersion(String routeId) {
            return version;
        }

        @Override
        public List<StopVisit> visits(final long versionId) {
            return visits;
        }
    }

    private static final class FakeFinder implements AccessFinder {
        private final Map<StopVisit, List<Access>> accesses = new HashMap<>();
        private StopVisit origin;
        private List<StopVisit> targets = List.of();
        private AccessConstraints constraints;

        @Override
        public Set<AccessMode> requiredModes() {
            return Set.of(AccessMode.WALK);
        }

        @Override
        public Map<StopVisit, List<Access>> find(StopVisit origin, List<StopVisit> targets,
            AccessConstraints constraints) {
            this.origin = origin;
            this.targets = targets;
            this.constraints = constraints;
            Map<StopVisit, List<Access>> found = new HashMap<>(accesses);
            found.keySet().retainAll(targets);
            return found;
        }
    }
}
