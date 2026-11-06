package com.gustler.backend.api.boarding.application;

import com.gustler.backend.api.boarding.BoardingOptionsException;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Option;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.RouteNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기준 방문에서 같은 노선의 다른 방문으로 이동해 탈 수 있는 후보를 만든다. 이동 방식과 무관한 순서만 맡는다.
 *
 * <p>기준 방문을 고르고, 관계·승차 가능 여부로 후보 방문을 거른 뒤, 요청한 수단으로 쓸 수 있는 {@link AccessFinder}마다
 * 이동 방법을 받아 정류장별로 합친다. 상한을 넘는 방법을 빼고 가장 빠른 방법 순서로 정렬해 {@code limit}개를 돌려준다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BoardingOptionsQueryService {
    private static final Comparator<Access> FASTEST = Comparator.comparingInt(Access::durationSeconds);
    private final BoardingOptionsQueryRepository repository;
    private final List<AccessFinder> finders;

    public BoardingOptionsQueryService(BoardingOptionsQueryRepository repository, List<AccessFinder> finders) {
        this.repository = repository;
        this.finders = List.copyOf(finders);
    }

    /**
     * 후보를 가장 빠른 이동 방법의 시간 오름차순(같으면 방문 순번)으로 돌려준다.
     * 요청한 수단을 모두 쓰는 계산기 조합이 없으면 {@code UNSUPPORTED_MODE}다.
     */
    public BoardingOptions get(BoardingOptionsQuery query) {
        List<AccessFinder> usable = usable(query.modes());
        final long version = repository.currentVersion(query.routeId().value()).orElseThrow(RouteNotFoundException::new);
        if (query.referenceVersionId() != null && query.referenceVersionId() != version) {
            throw new BoardingOptionsException(ErrorCode.REFERENCE_VERSION_CONFLICT);
        }
        List<StopVisit> visits = repository.visits(version);
        StopVisit origin = origin(query, visits);
        List<StopVisit> targets = targets(query, origin, visits);
        AccessConstraints constraints = new AccessConstraints(query.maxMinutes() * 60);
        Map<StopVisit, List<Access>> accesses = new LinkedHashMap<>();
        for (AccessFinder finder : usable) {
            finder.find(origin, targets, constraints)
                .forEach((target, found) -> accesses.computeIfAbsent(target, ignored -> new ArrayList<>()).addAll(found));
        }
        List<Option> options = new ArrayList<>();
        for (StopVisit target : targets) {
            List<Access> within = accesses.getOrDefault(target, List.of()).stream()
                .filter(access -> access.durationSeconds() <= constraints.maxSeconds()).sorted(FASTEST).toList();
            if (!within.isEmpty()) {
                options.add(new Option(target, target.stopSequence() - origin.stopSequence(),
                    StopRelation.of(origin, target), within));
            }
        }
        options.sort(Comparator.comparingInt(Option::fastestSeconds)
            .thenComparingInt(option -> option.stop().stopSequence()));
        return new BoardingOptions(query.routeId().value(), version, origin,
            options.subList(0, Math.min(options.size(), query.limit())));
    }

    private List<AccessFinder> usable(Set<AccessMode> modes) {
        List<AccessFinder> usable = finders.stream().filter(finder -> modes.containsAll(finder.requiredModes())).toList();
        Set<AccessMode> covered = EnumSet.noneOf(AccessMode.class);
        usable.forEach(finder -> covered.addAll(finder.requiredModes()));
        if (usable.isEmpty() || !covered.equals(modes)) {
            throw new BoardingOptionsException(ErrorCode.UNSUPPORTED_MODE);
        }
        return usable;
    }

    private static List<StopVisit> targets(BoardingOptionsQuery query, StopVisit origin, List<StopVisit> visits) {
        return visits.stream()
            .filter(visit -> visit.stopSequence() != origin.stopSequence())
            .filter(visit -> !query.boardingOnly() || visit.boardingAllowed())
            .filter(visit -> query.relations().contains(StopRelation.of(origin, visit)))
            .toList();
    }

    private static StopVisit origin(BoardingOptionsQuery query, List<StopVisit> visits) {
        List<StopVisit> candidates = visits.stream().filter(visit -> visit.stationId().equals(query.stationId()))
            .toList();
        if (candidates.isEmpty()) {
            throw new BoardingOptionsException(ErrorCode.STATION_NOT_FOUND);
        }
        if (query.stopSequence() != null) {
            return candidates.stream().filter(visit -> visit.stopSequence() == query.stopSequence()).findFirst()
                .orElseThrow(() -> new BoardingOptionsException(ErrorCode.REFERENCE_VERSION_CONFLICT));
        }
        if (candidates.size() > 1) {
            throw new BoardingOptionsException(ErrorCode.STOP_VISIT_AMBIGUOUS);
        }
        return candidates.getFirst();
    }
}
