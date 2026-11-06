package com.gustler.backend.worker.routegeometry.reference;

import com.gustler.backend.routecatalog.api.ReadRouteModelFacts;
import com.gustler.backend.routegeometry.api.RouteModelFacts;
import com.gustler.backend.routegeometry.engine.api.*;
import com.gustler.backend.routegeometry.engine.api.accuracy.*;
import com.gustler.backend.worker.routegeometry.*;
import java.time.Clock;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Opt-in candidate-only branch of the durable worker. No output is a RouteDisplayGeometry. */
public final class ReferenceShadowGeneration {
    public static final String ALGORITHM="native-reference-shadow-v1";
    private static final Logger LOG=LoggerFactory.getLogger(ReferenceShadowGeneration.class);
    public record Snapshot(RouteGeometry source,RouteModelFacts facts,ReferenceModelInput modelInput) {}
    public record Artifact(String format,String generationKey,String status,String reason,
            ReferenceModelInput modelInput,SourceRoleNativeGenerator.Result result,GlobalReferenceMatcherAdapter.Diagnostic globalResult,
            boolean modelAdopted,boolean published,String accuracyGrade) {}
    private final JdbcRouteDisplayGenerationStore store;
    private final RouteDisplaySourceReader sources;
    private final ReadRouteModelFacts facts;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final PinnedRouteSearchGraph graph;
    private final SourceRoleNativeGenerator generator;
    private final AccuracyCandidate.ModelIdentity model;
    private final String route;
    private final int fromSequence,toSequence;
    private final String policyDigest;
    public ReferenceShadowGeneration(JdbcRouteDisplayGenerationStore store,RouteDisplaySourceReader sources,
            ReadRouteModelFacts facts,ObjectMapper json,PlatformTransactionManager transactions,Clock clock,
            PinnedRouteSearchGraph graph) {
        this.store=store;this.sources=sources;this.facts=facts;this.json=json;this.transaction=new TransactionTemplate(transactions);
        this.clock=clock;this.graph=graph;
        var configuration=SourceRoleNativeGenerator.Config.frozenV3();this.generator=new SourceRoleNativeGenerator(configuration);
        var scope=graph.tree().path("scope");this.route=ReferenceJson.text(scope,"routeId");
        this.fromSequence=ReferenceJson.integer(scope.path("fromSequence"));this.toSequence=ReferenceJson.integer(scope.path("toSequence"));
        ReferenceJson.require(fromSequence>=1 && toSequence>fromSequence,"INVALID_SHADOW_SCOPE");
        this.policyDigest=RouteDisplayGeneration.sha256(ALGORITHM+"\nCANDIDATE_ONLY/NO_ADOPTION/NO_PUBLICATION\n"+route+"\n"+fromSequence+"\n"+toSequence);
        this.model=new AccuracyCandidate.ModelIdentity(SourceRoleNativeGenerator.REVISION,implementationDigest(),configuration.digest());
    }
    /** Only this current-source read enters the snapshot. Receipt IDs do not change semantic generation identity. */
    public void discover(String routeId) {
        if(!route.equals(routeId))return;
        transaction.executeWithoutResult(tx->sources.readLocked(route).ifPresent(raw->{
            if(raw.shape()==null || raw.busStops().isEmpty())return;
            var source=RouteDisplayGeneration.at(raw,clock.instant());var roles=readFacts(source);
            var input=identity(source,roles);var snapshot=new Snapshot(source,roles,input);
            store.enqueue(input.generationKey(),route,input.inputDigest(),input.evidenceRevision(),ALGORITHM,
                json.writeValueAsString(snapshot),source.shape().sourceManifestId());
        }));
    }
    public boolean execute(JdbcRouteDisplayGenerationStore.Job job) {
        Snapshot snapshot=json.readValue(job.inputJson(),Snapshot.class);
        if(!snapshotMatches(job,snapshot)) {store.supersede(job);return false;}
        String status,reason;SourceRoleNativeGenerator.Result result=null;
        try {
            result=generator.generate(snapshot.source(),snapshot.facts(),graph,fromSequence,toSequence);
            status=result.status();reason=result.reason();
        } catch(IllegalArgumentException unavailable) {
            status="INSUFFICIENT";reason=machineReason(unavailable.getMessage());
        } catch(RuntimeException failure) {
            status="ERROR";reason="GENERATION_EXCEPTION";
        }
        var global=new GlobalReferenceMatcherAdapter().generate(snapshot.source(),snapshot.facts(),graph);
        var artifact=new Artifact("route-native-shadow-artifact-v2",job.generationKey(),status,reason,
            snapshot.modelInput(),result,global,false,false,"UNEVALUATED");
        String document=json.writeValueAsString(artifact);
        if(!artifact.equals(json.readValue(document,Artifact.class)))
            throw new IllegalStateException("Serialized shadow artifact changed");
        boolean stored=Boolean.TRUE.equals(transaction.execute(tx->{
            var current=sources.readLocked(job.routeId());
            if(current.isEmpty() || current.get().shape()==null) {store.supersede(job);return false;}
            var now=RouteDisplayGeneration.at(current.get(),clock.instant());
            var currentInput=identity(now,readFacts(now));
            if(!snapshotMatches(job,snapshot) || !sources.snapshotProvenanceMatches(now,now.shape().sourceManifestId())
                    || !currentInput.semanticMatch(snapshot.modelInput())) {store.supersede(job);return false;}
            return store.complete(job,document,RouteDisplayGeneration.sha256(document));
        }));
        LOG.info("event=route_native_shadow_completed routeId={} generationKey={} status={} reason={} stored={} published=false modelAdopted=false",
            job.routeId(),job.generationKey(),status,reason,stored);
        return stored;
    }
    private boolean snapshotMatches(JdbcRouteDisplayGenerationStore.Job job,Snapshot snapshot) {
        if(!ALGORITHM.equals(job.algorithmRevision()) || snapshot.source()==null || snapshot.source().shape()==null
                || !route.equals(job.routeId()) || !route.equals(snapshot.source().routeId()) || snapshot.facts()==null
                || snapshot.modelInput()==null || !Objects.equals(job.sourceManifestId(),snapshot.source().shape().sourceManifestId())
                || !sources.snapshotProvenanceMatches(snapshot.source(),job.sourceManifestId()))return false;
        var actual=identity(snapshot.source(),snapshot.facts());
        return actual.equals(snapshot.modelInput()) && job.generationKey().equals(actual.generationKey())
            && job.inputDigest().equals(actual.inputDigest()) && job.evidenceRevision().equals(actual.evidenceRevision());
    }
    private RouteModelFacts readFacts(RouteGeometry source) {
        return facts.read(Set.of(RouteModelFacts.Role.CENTER_YN),RouteDisplayGeneration.input(source),source.referenceVersionId());
    }
    private ReferenceModelInput identity(RouteGeometry source,RouteModelFacts roles) {
        return ReferenceModelInput.of(source,roles,model,graph.digest(),policyDigest);
    }
    private static String machineReason(String value) {
        return value!=null && value.matches("[A-Z0-9_]{1,128}")?value:"GENERATION_INPUT_UNSUPPORTED";
    }
    /** Hash the actual runtime implementation, including nested record/configuration classes. */
    private static String implementationDigest() {
        Set<Class<?>> classes=new HashSet<>();
        for(Class<?> type:List.of(ReferenceShadowGeneration.class,SourceRoleNativeGenerator.class,ProviderSourceBinding.class,
                PinnedRouteSearchGraph.class,PinnedNativeCatalog.class,SourceSearchGeometry.class,ReferenceJson.class,CanonicalSourceObservations.class,TurnWitnessEvidence.class,GlobalReferenceMatcherAdapter.class,
                SourceObservationRouteMatcher.class,SourceObservationPreparation.class,NativeReferenceProjection.class,RouteDisplayGeneration.class,ReferenceModelInput.class,RouteGeometry.class,
                RouteModelFacts.class,com.gustler.backend.routegeometry.api.RouteDisplayInput.class))collect(type,classes);
        var identities=new ArrayList<String>();
        for(var type:classes.stream().sorted(Comparator.comparing(Class::getName)).toList()) {
            String resource="/"+type.getName().replace('.','/')+".class";
            try(var input=type.getResourceAsStream(resource)) {
                if(input==null)throw new IllegalStateException("Model implementation bytes unavailable");
                identities.add(type.getName()+":"+com.gustler.backend.routegeometry.api.RouteSourceReceipt.sha256(input.readAllBytes()));
            } catch(java.io.IOException failure) {throw new IllegalStateException("Model implementation bytes unavailable",failure);}
        }
        identities.add("PROJECTION_DEPENDENCIES:"+dependencyDigest(ReferenceShadowGeneration::resourceBytes));
        for(String artifact:List.of("proj4j","proj4j-epsg"))
            identities.add("RUNTIME_JAR:"+artifact+":"+dependencyJarDigest(artifact));
        return RouteDisplayGeneration.sha256(String.join("\n",identities));
    }
    private static byte[] resourceBytes(String resource) {
        try(var input=ReferenceShadowGeneration.class.getResourceAsStream(resource)) {
            if(input==null)throw new IllegalStateException("Projection dependency metadata unavailable");
            byte[] bytes=input.readNBytes(8_000_001);
            if(bytes.length==0 || bytes.length>8_000_000)throw new IllegalStateException("Projection dependency resource unsupported");
            return bytes;
        } catch(java.io.IOException failure) {throw new IllegalStateException("Projection dependency metadata unavailable",failure);}
    }
    static String dependencyDigest(java.util.function.Function<String,byte[]> resources) {
        var parts=new ArrayList<String>();
        for(String artifact:List.of("proj4j","proj4j-epsg")) {
            String path="/META-INF/maven/org.locationtech.proj4j/"+artifact+"/pom.properties";
            byte[] bytes=resources.apply(path);
            if(bytes==null || bytes.length==0 || bytes.length>8192)throw new IllegalStateException("Projection dependency metadata unavailable");
            var properties=new java.util.Properties();
            try {properties.load(new java.io.ByteArrayInputStream(bytes));}
            catch(java.io.IOException invalid) {throw new IllegalStateException("Projection dependency metadata invalid",invalid);}
            String version=properties.getProperty("version");
            if(!"org.locationtech.proj4j".equals(properties.getProperty("groupId")) || !artifact.equals(properties.getProperty("artifactId"))
                    || version==null || !version.matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,64}"))
                throw new IllegalStateException("Projection dependency metadata invalid");
            parts.add(artifact+":"+version+":"+com.gustler.backend.routegeometry.api.RouteSourceReceipt.sha256(bytes));
        }
        byte[] epsg=resources.apply("/proj4/nad/epsg");
        if(epsg==null || epsg.length==0 || epsg.length>8_000_000)throw new IllegalStateException("Projection definitions unavailable");
        parts.add("EPSG_DEFINITIONS:"+com.gustler.backend.routegeometry.api.RouteSourceReceipt.sha256(epsg));
        return RouteDisplayGeneration.sha256(String.join("\n",parts));
    }
    /** Canonical archive-entry fingerprint also detects a patched runtime JAR with unchanged Maven metadata.
     * JarURLConnection supports ordinary dependencies and Spring Boot nested dependency JARs. */
    private static String dependencyJarDigest(String artifact) {
        var metadata=ReferenceShadowGeneration.class.getResource("/META-INF/maven/org.locationtech.proj4j/"+artifact+"/pom.properties");
        try {
            if(metadata==null || !(metadata.openConnection() instanceof java.net.JarURLConnection connection))
                throw new IllegalStateException("Projection dependency archive unavailable");
            var jar=connection.getJarFile();var entries=java.util.Collections.list(jar.entries()).stream()
                .filter(entry->!entry.isDirectory()).sorted(Comparator.comparing(java.util.jar.JarEntry::getName)).toList();
            if(entries.isEmpty() || entries.size()>5000)throw new IllegalStateException("Projection dependency archive unsupported");
            var digest=java.security.MessageDigest.getInstance("SHA-256");long total=0;
            for(var entry:entries) {
                byte[] name=entry.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                long size=entry.getSize();if(size<0 || size>8_000_000)throw new IllegalStateException("Projection dependency archive unsupported");
                digest.update(java.nio.ByteBuffer.allocate(12).putInt(name.length).putLong(size).array());digest.update(name);
                try(var input=jar.getInputStream(entry)) {
                    byte[] buffer=new byte[8192];int read;long entryRead=0;
                    while((read=input.read(buffer))!=-1) {
                        total+=read;entryRead+=read;
                        if(total>32_000_000 || entryRead>size)throw new IllegalStateException("Projection dependency archive unsupported");
                        digest.update(buffer,0,read);
                    }
                    if(entryRead!=size)throw new IllegalStateException("Projection dependency archive unsupported");
                }
            }
            // The classloader owns the shared JarFile; do not close it.
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Projection dependency archive unavailable",unavailable);
        }
    }
    private static void collect(Class<?> type,Set<Class<?>> output) {
        if(!output.add(type))return;for(var nested:type.getDeclaredClasses())collect(nested,output);
    }
}
