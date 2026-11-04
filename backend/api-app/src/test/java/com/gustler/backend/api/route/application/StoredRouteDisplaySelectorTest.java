package com.gustler.backend.api.route.application;

import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static com.gustler.backend.routegeometry.api.RouteDisplaySamples.manual;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RoutePaths;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.*;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.*;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StoredRouteDisplaySelectorTest {
    final ObjectMapper json = new ObjectMapper();
    final Map<String,RouteDisplayRepository.Automatic> artifacts = new HashMap<>();
    final Map<String,RouteDisplayRepository.SourceManifest> manifests = new HashMap<>();
    final Set<String> corruptedManifests = new HashSet<>();
    final Map<String,RouteDisplayRepository.GenerationState> jobs = new HashMap<>();
    int jobQueries;
    RouteDisplayRepository.Manual reviewed;
    final RouteDisplayRepository repository = new RouteDisplayRepository() {
        public Map<String,GenerationState> findGenerationStates(String routeId,String digest,String evidence,List<String> keys) {
            jobQueries++;var found=new HashMap<String,GenerationState>();
            for(String key:keys)if(jobs.containsKey(key))found.put(key,jobs.get(key));
            return found;
        }
        public Optional<SourceManifest> findSourceManifest(String id) {
            if (corruptedManifests.contains(id)) throw new IllegalArgumentException("Corrupt sealed provenance");
            return Optional.ofNullable(manifests.get(id));
        }
        public Optional<Manual> findManual(String routeId, String digest) { return Optional.ofNullable(reviewed); }
        public Optional<Automatic> findAutomatic(String key,String routeId,String digest,String evidence,String algorithm) {
            return Optional.ofNullable(artifacts.get(key));
        }
    };
    final RouteDisplaySelector selector = new RouteDisplaySelector(repository,json,true);
    RouteGeometry geometry() {
        Point a=new Point(127,37), b=new Point(127.001,37), end=new Point(127,37.0001);
        return new RouteGeometry("204000057",1,"3330","A","B",2,
            List.of(new Stop("A",1,"A","UP",true,a),new Stop("B",2,"B","UP",true,b),
                new Stop("C",3,"C","DOWN",true,end)),
            new Shape(UUID.randomUUID(),Instant.EPOCH,List.of(a,b,new Point(127.001,37.0001),end)));
    }
    String key(RouteGeometry g,String algorithm) {
        return RouteDisplayGeneration.key(RouteDisplayGeneration.input(g).digest(),g.shape().evidence().revision(),algorithm,g.shape().sourceManifestId());
    }
    void store(RouteGeometry g,RouteDisplayGeometry display,String algorithm) {
        String text=json.writeValueAsString(display);
        artifacts.put(key(g,algorithm),new RouteDisplayRepository.Automatic(text,RouteDisplayGeneration.sha256(text)));
    }
    RouteDisplayGeometry build(RouteGeometry g) {return AutomaticRouteDisplay.build(g,RouteDisplayGeneration.input(g).digest());}
    @Test void 조회는_생성하지_않고_대기중에는_표시와_연결을_보류한다() {
        var result=selector.enrich(geometry()).display();
        assertThat(result.quality().status()).isEqualTo(QualityStatus.WITHHELD);
        assertThat(result.quality().issues()).extracting(QualityIssue::code).containsExactly("GENERATION_PENDING");
        assertThat(result.paths()).isNull();assertThat(result.connections()).isEmpty();assertThat(artifacts).isEmpty();
        var g=geometry();
        var noVisits=new RouteGeometry(g.routeId(),g.referenceVersionId(),g.name(),g.startPoint(),g.endPoint(),g.turnSequence(),List.of(),g.shape());
        assertThat(selector.enrich(noVisits).display().quality().issues()).extracting(QualityIssue::code).containsExactly("INVALID_SOURCE_INPUT");
    }
    @Test void 저장된_최신자동을_선택하고_현재수동이_있으면_항상_우선한다() {
        var g=geometry();var generated=build(g);store(g,generated,AutomaticRouteDisplay.REVISION);
        assertThat(selector.enrich(g).display()).usingRecursiveComparison().ignoringFields("revision").isEqualTo(generated);
        reviewed=new RouteDisplayRepository.Manual(json.writeValueAsString(manual(RouteDisplayGeneration.input(g),0.0001)),"reviewed");
        assertThat(selector.enrich(g).display().source()).isEqualTo(Source.MANUAL_REVIEWED);
        assertThat(selector.enrich(g).display().sourcePaths()).isEqualTo(generated.sourcePaths());
        assertThat(selector.enrich(g).display().paths()).isNotEqualTo(generated.sourcePaths());
    }
    @Test void 자동이_없으면_검증된_원본산출물을_선택한다() {
        var g=geometry();var source=AutomaticRouteDisplay.sourceOnly(g,RouteDisplayGeneration.input(g).digest());
        store(g,source,RouteDisplayGeneration.SOURCE_REVISION);
        assertThat(selector.enrich(g).display()).usingRecursiveComparison().ignoringFields("revision").isEqualTo(source);
        assertThat(source.quality().status()).isEqualTo(QualityStatus.SOURCE_ONLY);
    }
    @Test void 체크섬손상_및_다른원천판본을_표시하지않는다() {
        var g=geometry();var generated=build(g);store(g,generated,AutomaticRouteDisplay.REVISION);
        var stored=artifacts.get(key(g,AutomaticRouteDisplay.REVISION));
        artifacts.put(key(g,AutomaticRouteDisplay.REVISION),new RouteDisplayRepository.Automatic(stored.document(),"0".repeat(64)));
        assertThat(selector.enrich(g).display().paths()).isNull();
        store(g,generated,AutomaticRouteDisplay.REVISION);
        var changed=new RouteGeometry(g.routeId(),1,g.name(),g.startPoint(),g.endPoint(),2,g.busStops(),
            new Shape(g.shape().id(),Instant.EPOCH,List.of(new Point(127.00001,37),new Point(127.001,37),new Point(127,37.0001))));
        assertThat(selector.enrich(changed).display().paths()).isNull();
    }
    @Test void 해시가_일치해도_선분과_다른_연결점은_거부한다() {
        var g=geometry();var generated=build(g);var connections=new HashMap<>(generated.connections());
        var c=connections.get(1);
        connections.put(1,new Connection(new com.gustler.backend.routegeometry.api.RouteDisplayInput.Point(128,37),c.segmentIndex(),c.segmentFraction()));
        var broken=new RouteDisplayGeometry(generated.paths(),connections,Map.of(),generated.revision(),generated.source(),generated.evidence(),generated.quality());
        store(g,broken,AutomaticRouteDisplay.REVISION);
        assertThat(selector.enrich(g).display().paths()).isNull();
    }
    @Test void 품질등급이_같아도_선택산출물이_바뀌면_ETag판본이_달라진다() {
        var g=geometry();var source=AutomaticRouteDisplay.sourceOnly(g,RouteDisplayGeneration.input(g).digest());
        store(g,source,RouteDisplayGeneration.SOURCE_REVISION);
        var before=selector.enrich(g);
        var automatic=new RouteDisplayGeometry(source.paths(),source.connections(),Map.of(),source.revision(),
            source.source(),source.evidence(),new Quality(QualityStatus.SOURCE_ONLY,
                List.of(new QualityIssue("CANDIDATE_REJECTED",null,null,null))));
        store(g,automatic,AutomaticRouteDisplay.REVISION);
        var after=selector.enrich(g);
        assertThat(after.display().quality().status()).isEqualTo(before.display().quality().status());
        assertThat(after.revision()).isNotEqualTo(before.revision());
    }
    @Test void 자동원본경로가_없거나_손상돼도_수동표시는_유지한다() {
        var g=geometry();
        reviewed=new RouteDisplayRepository.Manual(json.writeValueAsString(manual(RouteDisplayGeneration.input(g),0.0001)),"reviewed");
        var before=selector.enrich(g);
        assertThat(before.display().source()).isEqualTo(Source.MANUAL_REVIEWED);
        assertThat(before.display().sourcePaths()).isNull();
        artifacts.put(key(g,AutomaticRouteDisplay.REVISION),new RouteDisplayRepository.Automatic("{}","invalid"));
        assertThat(selector.enrich(g).display()).isEqualTo(before.display());
        store(g,build(g),AutomaticRouteDisplay.REVISION);
        var after=selector.enrich(g);
        assertThat(after.display().source()).isEqualTo(Source.MANUAL_REVIEWED);
        assertThat(after.display().sourcePaths()).isNotNull();
        assertThat(after.display().paths()).isEqualTo(before.display().paths());
        assertThat(after.revision()).isNotEqualTo(before.revision());
    }
    @Test void 체크섬이_정상이어도_원본경로를_표시경로로_바꾼_산출물은_거부한다() {
        var g=geometry();var generated=build(g);
        var changed=new ArrayList<>(generated.sourcePaths().up().points());
        var first=changed.getFirst();changed.set(0,new Point(first.x()+0.00001,first.y()));
        var sourcePaths=new RoutePaths(new RoutePaths.Path(changed),generated.sourcePaths().down());
        var corrupted=new RouteDisplayGeometry(generated.paths(),generated.connections(),Map.of(),
            generated.revision(),generated.source(),generated.evidence(),generated.quality(),sourcePaths);
        store(g,corrupted,AutomaticRouteDisplay.REVISION);
        var result=selector.enrich(g).display();
        assertThat(result.quality().status()).isEqualTo(QualityStatus.WITHHELD);
        assertThat(result.paths()).isNull();assertThat(result.sourcePaths()).isNull();
    }
    RouteGeometry captured(RouteGeometry g, String manifestId) {
        var shape=g.shape();
        return new RouteGeometry(g.routeId(),g.referenceVersionId(),g.name(),g.startPoint(),g.endPoint(),
            g.turnSequence(),g.busStops(),new Shape(shape.id(),shape.receivedAt(),shape.points(),shape.evidence(),manifestId));
    }
    void recordManifest(RouteGeometry g) {
        manifests.put(g.shape().sourceManifestId(),new RouteDisplayRepository.SourceManifest(
            RouteDisplayGeneration.input(g).digest(),g.shape().evidence().revision(),g.referenceVersionId()));
    }
    @Test void 유효한_수집근거의_수신기록만_갱신되면_같은_자동산출물과_ETag를_유지한다() {
        var g=captured(geometry(),"a".repeat(64));recordManifest(g);
        store(g,build(g),AutomaticRouteDisplay.REVISION);
        var first=selector.enrich(g);
        assertThat(first.display().quality().status()).isNotEqualTo(QualityStatus.WITHHELD);
        var refreshed=captured(g,"b".repeat(64));recordManifest(refreshed);
        assertThat(key(refreshed,AutomaticRouteDisplay.REVISION)).isEqualTo(key(g,AutomaticRouteDisplay.REVISION));
        var next=selector.enrich(refreshed);
        assertThat(next.display()).isEqualTo(first.display());
        assertThat(next.revision()).isEqualTo(first.revision());
    }
    @Test void 기존_미수집산출물을_수집근거가_있는_산출물로_재명명하지않는다() {
        var legacy=geometry();store(legacy,build(legacy),AutomaticRouteDisplay.REVISION);
        assertThat(selector.enrich(legacy).display().paths()).isNotNull();
        var g=captured(legacy,"a".repeat(64));recordManifest(g);
        assertThat(key(g,AutomaticRouteDisplay.REVISION)).isNotEqualTo(key(legacy,AutomaticRouteDisplay.REVISION));
        var pending=selector.enrich(g);
        assertThat(pending.display().quality().issues()).extracting(QualityIssue::code).containsExactly("GENERATION_PENDING");
        store(g,build(g),AutomaticRouteDisplay.REVISION);
        var ready=selector.enrich(g);
        assertThat(ready.display().paths()).isNotNull();
        assertThat(ready.revision()).isNotEqualTo(pending.revision());
    }
    @Test void 이전방문_근거를_새좌표에_붙이지않고_새입력의_미수집산출물을_선택한다() {
        var old=captured(geometry(),"a".repeat(64));recordManifest(old);
        var visits=new ArrayList<>(old.busStops());var stop=visits.getFirst();
        visits.set(0,new Stop(stop.stationId(),stop.stopSequence(),stop.name(),stop.direction(),stop.boardingAllowed(),
            new Point(stop.point().x()+0.00001,stop.point().y())));
        var changed=new RouteGeometry(old.routeId(),old.referenceVersionId(),old.name(),old.startPoint(),old.endPoint(),
            old.turnSequence(),visits,old.shape());
        store(changed,build(changed),AutomaticRouteDisplay.REVISION);
        var pending=selector.enrich(changed);
        assertThat(pending.shape().sourceManifestId()).isNull();
        assertThat(pending.display().quality().issues()).extracting(QualityIssue::code).containsExactly("GENERATION_PENDING");
        var legacy=captured(changed,null);store(legacy,build(legacy),AutomaticRouteDisplay.REVISION);
        var ready=selector.enrich(changed);
        assertThat(ready.shape().sourceManifestId()).isNull();
        assertThat(ready.display().paths()).isNotNull();
    }
    @Test void 누락되거나_손상된_수집근거는_자동을_보류하지만_정상수동은_유지한다() {
        for (boolean corrupt : List.of(false,true)) {
            var g=captured(geometry(),"c".repeat(64));
            if(corrupt) corruptedManifests.add(g.shape().sourceManifestId());
            store(g,build(g),AutomaticRouteDisplay.REVISION);
            reviewed=null;
            var blocked=selector.enrich(g);
            assertThat(blocked.display().quality().issues()).extracting(QualityIssue::code)
                .containsExactly("INVALID_SOURCE_PROVENANCE");
            assertThat(blocked.display().paths()).isNull();
            reviewed=new RouteDisplayRepository.Manual(json.writeValueAsString(manual(RouteDisplayGeneration.input(g),0.0001)),"reviewed");
            var manual=selector.enrich(g).display();
            assertThat(manual.source()).isEqualTo(Source.MANUAL_REVIEWED);
            assertThat(manual.paths()).isNotNull();
            assertThat(manual.sourcePaths()).isNull();
        }
    }
    @Test void 원본에_실제로_있는_회차중복점을_보존한_보류산출물은_대기중으로_오인하지않는다() {
        var old=geometry();var a=old.busStops().getFirst().point();var b=old.busStops().get(1).point();var end=old.busStops().getLast().point();
        var g=new RouteGeometry(old.routeId(),old.referenceVersionId(),old.name(),old.startPoint(),old.endPoint(),old.turnSequence(),old.busStops(),
            new Shape(old.shape().id(),old.shape().receivedAt(),List.of(a,b,b,end)));
        var sourcePaths=new RoutePaths(new RoutePaths.Path(List.of(a,b)),new RoutePaths.Path(List.of(b,end)));
        String digest=RouteDisplayGeneration.input(g).digest();
        var stored=new RouteDisplayGeometry(null,Map.of(),Map.of(),AutomaticRouteDisplay.REVISION+"/"+digest+"/"+g.shape().evidence().revision()+"/WITHHELD",
            Source.AUTOMATIC_FALLBACK,g.shape().evidence().sources(),new Quality(QualityStatus.WITHHELD,List.of(new QualityIssue("STOP_CONNECTION_GAP","UP",2,96.0))),sourcePaths);
        store(g,stored,AutomaticRouteDisplay.REVISION);
        jobs.put(key(g,RouteDisplayGeneration.SOURCE_REVISION),new RouteDisplayRepository.GenerationState("COMPLETED",false));
        var actual=selector.enrich(g).display();
        assertThat(actual.paths()).isNull();assertThat(actual.quality().status()).isEqualTo(QualityStatus.WITHHELD);
        assertThat(actual.quality().issues()).extracting(QualityIssue::code).containsExactly("STOP_CONNECTION_GAP");
        assertThat(actual.sourcePaths()).isEqualTo(sourcePaths);assertThat(actual.revision()).contains("/stored/");
    }

    RouteDisplayGeometry withheld(RouteGeometry g) {
        String digest=RouteDisplayGeneration.input(g).digest();
        return new RouteDisplayGeometry(null,Map.of(),Map.of(),AutomaticRouteDisplay.REVISION+"/"+digest+"/"+g.shape().evidence().revision()+"/WITHHELD",
                Source.AUTOMATIC_FALLBACK,g.shape().evidence().sources(),
                new Quality(QualityStatus.WITHHELD,List.of(new QualityIssue("STOP_CONNECTION_GAP","UP",2,96.0))),g.paths());
    }
    void state(RouteGeometry g,String algorithm,String status,boolean canProgress) {
        jobs.put(key(g,algorithm),new RouteDisplayRepository.GenerationState(status,canProgress));
    }
    @Test void 개선이_보류돼도_유효한_현재원본을_선택하고_작업조회하지않는다() {
        var g=geometry();store(g,withheld(g),AutomaticRouteDisplay.REVISION);
        var source=AutomaticRouteDisplay.sourceOnly(g,RouteDisplayGeneration.input(g).digest());
        store(g,source,RouteDisplayGeneration.SOURCE_REVISION);
        assertThat(selector.enrich(g).display()).usingRecursiveComparison().ignoringFields("revision").isEqualTo(source);
        assertThat(jobQueries).isZero();
    }
    @Test void 유효한_개선을_찾으면_원본이나_작업상태를_추가조회하지않는다() {
        var g=geometry();store(g,build(g),AutomaticRouteDisplay.REVISION);
        assertThat(selector.enrich(g).display().paths()).isNotNull();assertThat(jobQueries).isZero();
    }
    @Test void 원본만_보류되고_개선이_실행중이면_최종실패가_아니다() {
        var g=geometry();store(g,withheld(g),RouteDisplayGeneration.SOURCE_REVISION);
        state(g,AutomaticRouteDisplay.REVISION,"RUNNING",true);
        var value=selector.enrich(g).display();
        assertThat(value.quality().issues()).extracting(QualityIssue::code).containsExactly("GENERATION_PENDING");
        assertThat(value.sourcePaths()).isEqualTo(g.paths());assertThat(jobQueries).isEqualTo(1);
    }
    @Test void 개선만_보류되고_원본이_대기중이어도_최종실패가_아니다() {
        var g=geometry();store(g,withheld(g),AutomaticRouteDisplay.REVISION);
        state(g,RouteDisplayGeneration.SOURCE_REVISION,"PENDING",true);
        assertThat(selector.enrich(g).display().quality().issues()).extracting(QualityIssue::code).containsExactly("GENERATION_PENDING");
    }
    @Test void 두작업이_실패하거나_소진되면_종료하고_판본도_바뀐다() {
        var g=geometry();var pending=selector.enrich(g);
        for(String status:List.of("FAILED","PENDING","RUNNING","COMPLETED")) {
            state(g,AutomaticRouteDisplay.REVISION,status,false);
            state(g,RouteDisplayGeneration.SOURCE_REVISION,status,false);
            var terminal=selector.enrich(g);
            assertThat(terminal.display().quality().issues()).extracting(QualityIssue::code).containsExactly("GENERATION_FAILED");
            assertThat(terminal.revision()).isNotEqualTo(pending.revision());
            assertThat(terminal.display().paths()).isNull();assertThat(terminal.display().connections()).isEmpty();
        }
    }
    @Test void 한도가_소진돼도_마지막lease가_유효하면_기다린다() {
        var g=geometry();state(g,AutomaticRouteDisplay.REVISION,"RUNNING",true);
        state(g,RouteDisplayGeneration.SOURCE_REVISION,"FAILED",false);
        assertThat(selector.enrich(g).display().quality().issues()).extracting(QualityIssue::code).containsExactly("GENERATION_PENDING");
    }
    @Test void 손상된_완료산출물은_다시생성중으로_오인하지않는다() {
        var g=geometry();artifacts.put(key(g,AutomaticRouteDisplay.REVISION),new RouteDisplayRepository.Automatic("{}","bad"));
        state(g,RouteDisplayGeneration.SOURCE_REVISION,"FAILED",false);
        assertThat(selector.enrich(g).display().quality().issues()).extracting(QualityIssue::code).containsExactly("GENERATION_FAILED");
    }

}
