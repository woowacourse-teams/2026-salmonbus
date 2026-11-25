package com.gustler.backend.routecatalog.application;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.routecatalog.domain.CatalogSourceBinding;
import com.gustler.backend.routecatalog.domain.CurrentRouteVersionQuery;
import com.gustler.backend.routecatalog.domain.RouteRegistry;
import com.gustler.backend.routecatalog.domain.RouteSource;
import com.gustler.backend.routecatalog.domain.RouteSourceResult;
import com.gustler.backend.routecatalog.domain.UpstreamRoute;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집을 시작하려면 노선 버전이 먼저 있어야 한다. observation_batch.route_version_id가 NOT NULL이므로
 * 노선 버전 없이는 수집 배치를 생성할 수 없다.
 *
 * <p>판본 조회는 DB만 읽는다. 노선 준비와 갱신은 Worker의 별도 작업이 refresh를 호출하여 수행한다.
 */
@Component
public class RouteCatalogLoader implements CurrentRouteVersion, RefreshRouteCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteCatalogLoader.class);

    private final Map<String, String> knownNames = new LinkedHashMap<>();

    private final CurrentRouteVersionQuery currentRouteVersionQuery;
    private final ApiCallQuota apiCallQuota;
    private final RouteSource routeSource;
    private final RouteRegistry routeRegistry;
    private final RouteVersionLoader routeVersionLoader;
    private final TransactionTemplate transactionTemplate;
    private final RouteSourceArchive archive;
    private final CatalogSourceBinding sourceBinding;

    public RouteCatalogLoader(
        CurrentRouteVersionQuery currentRouteVersionQuery,
        ApiCallQuota apiCallQuota,
        RouteSource routeSource,
        RouteRegistry routeRegistry,
        RouteVersionLoader routeVersionLoader,
        TransactionTemplate transactionTemplate
    ) {
        this(
                currentRouteVersionQuery,
                apiCallQuota,
                routeSource,
                routeRegistry,
                routeVersionLoader,
                transactionTemplate,
                Optional.empty(),
                Optional.empty()
        );
    }

    @Autowired
    public RouteCatalogLoader(
        CurrentRouteVersionQuery currentRouteVersionQuery,
        ApiCallQuota apiCallQuota,
        RouteSource routeSource,
        RouteRegistry routeRegistry,
        RouteVersionLoader routeVersionLoader,
        TransactionTemplate transactionTemplate,
        Optional<RouteSourceArchive> archive,
        Optional<CatalogSourceBinding> sourceBinding
    ) {
        this.archive = archive.orElse(RouteSourceArchive.NONE);
        this.sourceBinding = sourceBinding.orElse(CatalogSourceBinding.NONE);
        this.currentRouteVersionQuery = currentRouteVersionQuery;
        this.apiCallQuota = apiCallQuota;
        this.routeSource = routeSource;
        this.routeRegistry = routeRegistry;
        this.routeVersionLoader = routeVersionLoader;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Optional<RouteReference> findCurrentVersion(String sourceRouteId) {
        OptionalLong opened = currentRouteVersionQuery.findIdOf(sourceRouteId);
        if (opened.isPresent()) {
            return Optional.of(new RouteReference(opened.getAsLong()));
        }

        return Optional.empty();
    }

    @Override
    public List<String> currentGbisRouteIds() {
        return currentRouteVersionQuery.findCurrentGbisRouteIds();
    }

    @Override
    public synchronized String knownRouteName(String sourceRouteId) {
        return knownNames.getOrDefault(sourceRouteId, "");
    }

    @Override
    public Optional<RouteReference> refresh(String sourceRouteId, OffsetDateTime readAt) {
        OptionalLong updated = openFromUpstream(sourceRouteId, readAt);
        if (updated.isPresent()) {
            return Optional.of(new RouteReference(updated.getAsLong()));
        }

        return Optional.empty();
    }

    private synchronized void rememberName(UpstreamRoute route) {
        if (!knownNames.containsKey(route.sourceRouteId()) && knownNames.size() >= 256) {
            knownNames.remove(knownNames.keySet().iterator().next());
        }

        knownNames.put(route.sourceRouteId(), Objects.toString(route.displayName(), ""));
    }

    private OptionalLong openFromUpstream(String sourceRouteId, OffsetDateTime readAt) {
        if (!apiCallQuota.reserveRouteCatalog(readAt, routeSource.requiredCallsPerRead())) {
            archive.record("ROUTE_CATALOG_ACQUISITION", List.of(receipt(sourceRouteId, readAt,
                RouteSourceReceipt.Status.NO_QUOTA)), List.of());
            LOGGER.warn("하루 호출 한도가 남지 않아 노선정보를 갱신하지 못했다. 노선={}",
                sourceRouteId);
            return OptionalLong.empty();
        }

        // 호출 두 번을 먼저 예약한다. 노선정보 조회가 실패해 정류소를 조회하지 않아도 예약은 유지한다.
        // 예약을 취소하려 해도 이미 전송한 호출에 해당하는지 여기서는 확인할 수 없다.

        RouteSource.Capture captured = capture(sourceRouteId, readAt);
        List<RouteSourceReceipt> receipts = captured.receipts();
        if (receipts.isEmpty()) {
            receipts = List.of(receipt(sourceRouteId, readAt, RouteSourceReceipt.Status.UNRECORDED_LEGACY));
        }

        String manifest = archive.record("ROUTE_CATALOG_ACQUISITION", receipts, List.of());

        return switch (captured.result()) {
            case RouteSourceResult.Failed failed -> {
                LOGGER.warn("노선정보를 읽지 못해 판본을 못 열었다. 노선={} 사유=SOURCE_UNAVAILABLE", sourceRouteId);
                yield OptionalLong.empty();
            }
            case RouteSourceResult.Success success -> {
                rememberName(success.route());
                yield OptionalLong.of(open(success.route(), readAt, manifest));
            }
        };
    }

    private RouteSource.Capture capture(String sourceRouteId, OffsetDateTime readAt) {
        if (archive == RouteSourceArchive.NONE) {
            return new RouteSource.Capture(routeSource.read(sourceRouteId), List.of());
        }

        try {
            return Objects.requireNonNull(routeSource.capture(sourceRouteId));
        } catch (RuntimeException failure) {
            return new RouteSource.Capture(new RouteSourceResult.Failed("CATALOG_CAPTURE_FAILED"),
                List.of(receipt(sourceRouteId, readAt, RouteSourceReceipt.Status.TRANSPORT_FAILURE)));
        }
    }

    /** HTTP를 기다리는 동안 연결을 점유하지 않도록 노선·판본·원본 연결을 게시하는 부분만 트랜잭션으로 묶는다. */
    private long open(UpstreamRoute route, OffsetDateTime readAt, String acquisitionManifestId) {
        return transactionTemplate.execute(status -> {
            final long versionId = routeVersionLoader.load(
                    routeRegistry.register(route),
                    route.stops(),
                    route.timetable(),
                    readAt
            );

            if (acquisitionManifestId != null) {
                sourceBinding.bind(
                        versionId,
                        route,
                        acquisitionManifestId,
                        readAt
                );
            }

            return versionId;
        });
    }

    private static RouteSourceReceipt receipt(String routeId, OffsetDateTime at, RouteSourceReceipt.Status status) {
        String safeRouteId = "UNSPECIFIED";
        if (routeId.matches("[0-9]{1,20}")) {
            safeRouteId = routeId;
        }

        return new RouteSourceReceipt(
                "GBIS_API",
                "ROUTE_CATALOG",
                safeRouteId,
                status,
                null,
                at.toInstant(),
                "NOT_PROVIDED",
                RouteSourceReceipt.CoordinateSystem.UNKNOWN,
                null
        );
    }
}
