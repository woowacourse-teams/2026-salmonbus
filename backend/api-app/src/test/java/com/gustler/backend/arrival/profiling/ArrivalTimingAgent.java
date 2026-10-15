package com.gustler.backend.arrival.profiling;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.io.BufferedWriter;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** 테스트에서 생성한 javaagent JAR에만 담는다. 제품 코드·응답 헤더는 변경하지 않는다. */
public final class ArrivalTimingAgent {
    public static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static BufferedWriter output;
    private static final Map<String, String> SECTIONS = Map.ofEntries(
        Map.entry("com.zaxxer.hikari.HikariDataSource.getConnection", "pool.acquire"),
        Map.entry("org.postgresql.jdbc.PgPreparedStatement.executeQuery", "jdbc.execute"),
        Map.entry("com.gustler.backend.api.arrival.infrastructure.jdbc.JdbcArrivalReadRepository.visits", "repository.visits"),
        Map.entry("com.gustler.backend.api.arrival.infrastructure.jdbc.JdbcArrivalReadRepository.latest", "repository.latest"),
        Map.entry("com.gustler.backend.api.arrival.application.ArrivalQueryService.get", "query.service"),
        Map.entry("com.gustler.backend.api.arrival.dto.StationArrivalsResponse.from", "response.dto"),
        Map.entry("org.springframework.http.converter.AbstractJacksonHttpMessageConverter.writeInternal", "response.write")
    );

    public static void premain(String destination, Instrumentation instrumentation) throws Exception {
        output = Files.newBufferedWriter(Path.of(destination), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { output.close(); } catch (Exception ignored) { }
        }));
        var builder = new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .type(named("org.apache.catalina.core.ApplicationFilterChain"))
            .transform((target, type, loader, module, domain) -> target.visit(Advice.to(RequestAdvice.class)
                .on(named("doFilter").and(takesArguments(2)))))
            .type(named("com.zaxxer.hikari.HikariDataSource"))
            .transform((target, type, loader, module, domain) -> target.visit(Advice.to(TimedAdvice.class)
                .on(named("getConnection").and(takesArguments(0)))))
            .type(named("org.postgresql.jdbc.PgPreparedStatement"))
            .transform((target, type, loader, module, domain) -> target.visit(Advice.to(TimedAdvice.class)
                .on(named("executeQuery").and(takesArguments(0)))))
            .type(named("com.gustler.backend.api.arrival.infrastructure.jdbc.JdbcArrivalReadRepository"))
            .transform((target, type, loader, module, domain) -> target.visit(Advice.to(TimedAdvice.class)
                .on(named("visits").or(named("latest")))))
            .type(named("com.gustler.backend.api.arrival.application.ArrivalQueryService"))
            .transform((target, type, loader, module, domain) -> target.visit(Advice.to(TimedAdvice.class).on(named("get"))))
            .type(named("com.gustler.backend.api.arrival.dto.StationArrivalsResponse"))
            .transform((target, type, loader, module, domain) -> target.visit(Advice.to(TimedAdvice.class).on(named("from"))))
            .type(named("tools.jackson.databind.ObjectMapper"))
            .transform((target, type, loader, module, domain) -> target.visit(Advice.to(DecodeAdvice.class)
                .on(named("readValue").and(takesArguments(String.class, Class.class)))))
            .type(named("org.springframework.http.converter.AbstractJacksonHttpMessageConverter"))
            .transform((target, type, loader, module, domain) -> target.visit(Advice.to(TimedAdvice.class)
                .on(named("writeInternal").and(takesArguments(4)))));
        builder.installOn(instrumentation);
    }

    public static final class Scope {
        public final String id;
        public final long started = System.nanoTime();
        public final Map<String, Long> nanos = new LinkedHashMap<>();
        public final Map<String, Integer> calls = new LinkedHashMap<>();

        public Scope(String id) { this.id = id; }
    }

    public static boolean begin(Object request) throws Exception {
        if (CURRENT.get() != null) return false;
        Object id = request.getClass().getMethod("getHeader", String.class).invoke(request, "X-ETA-Measure");
        if (!(id instanceof String value) || !value.matches("[a-zA-Z0-9_-]{1,80}")) return false;
        CURRENT.set(new Scope(value));
        return true;
    }

    public static long started() {
        return CURRENT.get() == null ? 0 : System.nanoTime();
    }

    public static void finish(String method, long started) {
        Scope scope = CURRENT.get();
        if (scope == null || started == 0) return;
        String section = method.equals("snapshot.decode") ? method : SECTIONS.get(method);
        if (section != null) {
            scope.nanos.merge(section, System.nanoTime() - started, Long::sum);
            scope.calls.merge(section, 1, Integer::sum);
        }
    }

    public static void end(boolean root) throws Exception {
        if (!root) return;
        Scope scope = CURRENT.get();
        scope.nanos.put("server.filter", System.nanoTime() - scope.started);
        CURRENT.remove();
        StringBuilder row = new StringBuilder("{\"id\":\"").append(scope.id).append("\",\"nanos\":{");
        boolean first = true;
        for (var value : scope.nanos.entrySet()) {
            if (!first) row.append(',');
            first = false;
            row.append('"').append(value.getKey()).append("\":").append(value.getValue());
        }
        row.append("},\"calls\":{");
        first = true;
        for (var value : scope.calls.entrySet()) {
            if (!first) row.append(',');
            first = false;
            row.append('"').append(value.getKey()).append("\":").append(value.getValue());
        }
        row.append("}}\n");
        synchronized (ArrivalTimingAgent.class) { output.write(row.toString()); output.flush(); }
    }

    public static final class RequestAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static boolean enter(@Advice.Argument(0) Object request) throws Exception { return begin(request); }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter boolean root) throws Exception { end(root); }
    }

    public static final class TimedAdvice {
        @Advice.OnMethodEnter
        public static long enter() { return started(); }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started, @Advice.Origin("#t.#m") String method) {
            finish(method, started);
        }
    }

    public static final class DecodeAdvice {
        @Advice.OnMethodEnter
        public static long enter(@Advice.Argument(1) Class<?> target) {
            return target.getName().equals("com.gustler.backend.arrival.contract.ArrivalSnapshot") ? started() : 0;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter long started) { finish("snapshot.decode", started); }
    }
}
