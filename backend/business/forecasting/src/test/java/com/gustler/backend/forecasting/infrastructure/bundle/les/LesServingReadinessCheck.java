package com.gustler.backend.forecasting.infrastructure.bundle.les;

import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.les.LesForecastModel;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 로컬 모델의 전체 노선과 배포 식별을 검사한다. 운영 DB나 외부 API에 접근하지 않는다. */
public final class LesServingReadinessCheck {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LesServingReadinessCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || Files.exists(Path.of(args[2]))) {
            throw new IllegalArgumentException("mode, bundle, new output file, release id required");
        }
        final boolean serving = args[0].equals("serving");
        if (!serving && !args[0].equals("candidate")) {
            throw new IllegalArgumentException("candidate or serving required");
        }
        Path directory = Path.of(args[1]);
        LesInferenceBundle bundle = readBundle(directory, serving);
        ModelRelease release = readRelease(directory, bundle, args[3], serving);
        verifyIdentity(release);
        verifyRoutes(directory, bundle, release);
        Map<String, Object> report = Map.of(
            "passed", true, "bundle_manifest_sha256", bundle.digest(), "routes", bundle.routes(),
            "input_profile", LesRouteStatistics.CONFIRMED_PROFILE, "release_id", release.releaseId(),
            "data_through", release.dataThrough(), "verified_cases", bundle.routes().size(),
            "mode", args[0], "scope", "local model, catalog, registry and deployment identity; no production activation");
        Files.writeString(Path.of(args[2]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println(JSON.writeValueAsString(report));
    }

    private static LesInferenceBundle readBundle(Path directory, final boolean serving) throws Exception {
        if (serving) {
            return LesInferenceBundle.loadForServing(directory);
        }
        return LesInferenceBundle.load(directory);
    }

    private static ModelRelease readRelease(
        Path directory,
        LesInferenceBundle bundle,
        String releaseId,
        final boolean serving
    ) throws Exception {
        if (serving) {
            ModelRelease release = new FileModelBundleLoader().filesUnder(directory.toString()).load();
            if (!release.releaseId().equals(releaseId)) {
                throw new IllegalStateException("Release identity mismatch");
            }
            return release;
        }
        Instant through = Instant.MIN;
        for (String route : bundle.routes()) {
            for (String field : List.of("events_known_through", "observations_through")) {
                Instant used = OffsetDateTime.parse(bundle.statistics(route).path(field).asText().replace(' ', 'T'))
                    .toInstant();
                if (used.isAfter(through)) {
                    through = used;
                }
            }
        }
        return ModelRelease.les(releaseId, bundle.digest(), through.toString(),
            new SupportedForecastScope(bundle.routes()), LesServingBundle.readModel(bundle));
    }

    private static void verifyIdentity(ModelRelease release) {
        LoadedModelRegistry registry = new LoadedModelRegistry();
        registry.add(release);
        ModelRelease loaded = registry.find(release.bundleDigest()).orElseThrow();
        ActiveModelDeployment deployment = new ActiveModelDeployment(
            1, release.featureContractVersion(), release.releaseId(), release.bundleDigest());
        if (!loaded.hasIdentityOf(deployment)) {
            throw new IllegalStateException("Registry/deployment identity mismatch");
        }
    }

    private static void verifyRoutes(Path directory, LesInferenceBundle bundle, ModelRelease release) throws Exception {
        JsonNode references = JSON.readTree(Files.readAllBytes(directory.resolve("route-reference.json")));
        LesForecastModel model = (LesForecastModel) release.model();
        List<String> verified = new ArrayList<>();
        for (JsonNode reference : references.path("routes")) {
            String route = reference.path("modelRoute").asText();
            List<RouteStop> stops = new ArrayList<>();
            for (JsonNode stop : reference.path("stops")) {
                stops.add(new RouteStop(1, stop.path("order").asInt(), stop.path("id").asText(),
                    stop.path("boardingAllowed").asBoolean(), RouteDirection.valueOf(stop.path("direction").asText())));
            }
            RouteStops catalog = new RouteStops(1, reference.path("sourceRouteId").asText(), stops, route);
            if (!release.scope().coversSourceRoute(catalog.sourceRouteId())) {
                throw new IllegalStateException("Unsupported source route " + route);
            }
            LesRouteStatistics statistics = model.statistics(catalog);
            if (!statistics.route().equals(route) || !statistics.confirmedProfile()) {
                throw new IllegalStateException("Route/profile mismatch " + route);
            }
            verified.add(route);
        }
        if (!verified.equals(bundle.routes())) {
            throw new IllegalStateException("Catalog coverage mismatch");
        }
    }
}
