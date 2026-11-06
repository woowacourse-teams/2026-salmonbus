package com.gustler.backend.worker.routegeometry.reference;

import com.gustler.backend.routecatalog.api.ReadRouteModelFacts;
import com.gustler.backend.worker.routegeometry.JdbcRouteDisplayGenerationStore;
import com.gustler.backend.worker.routegeometry.RouteDisplaySourceReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Local pinned reference input only. A packet never chooses a path to open or grants adoption. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"route-geometry.generation.enabled","route-geometry.generation.shadow.enabled"},havingValue="true")
public class ReferenceShadowConfiguration {
    @Bean public ReferenceShadowGeneration referenceShadowGeneration(JdbcRouteDisplayGenerationStore store,
            RouteDisplaySourceReader sources,ReadRouteModelFacts facts,ObjectMapper json,
            PlatformTransactionManager transactions,Optional<Clock> clock,
            @Value("${route-geometry.generation.shadow.bundle-path}") String bundle,
            @Value("${route-geometry.generation.shadow.bundle-sha256}") String digest,
            @Value("${route-geometry.generation.shadow.raw-source-sha256}") String rawDigest,
            @Value("${route-geometry.generation.shadow.model-input-sha256}") Set<String> inputDigests) throws java.io.IOException {
        Path path=Path.of(bundle).toAbsolutePath();ReferenceJson.require(Files.isRegularFile(path) && Files.size(path)<=16_000_000,"INVALID_SHADOW_BUNDLE_FILE");
        byte[] bytes;
        try(InputStream file=Files.newInputStream(path);InputStream input=bundle.endsWith(".gz")?new GZIPInputStream(file):file) {
            bytes=input.readNBytes(16_000_001);ReferenceJson.require(bytes.length<=16_000_000,"JSON_TOO_LARGE");
        }
        var graph=PinnedRouteSearchGraph.load(bytes,new PinnedRouteSearchGraph.Pin(digest,rawDigest,inputDigests),json);
        return new ReferenceShadowGeneration(store,sources,facts,json,transactions,clock.orElseGet(Clock::systemUTC),graph);
    }
}
