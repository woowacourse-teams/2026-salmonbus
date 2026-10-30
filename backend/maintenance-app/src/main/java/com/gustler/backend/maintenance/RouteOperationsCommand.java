package com.gustler.backend.maintenance;

import com.gustler.backend.maintenance.configuration.RouteOperationsCommandConfiguration;
import com.gustler.backend.maintenance.db.DatabaseConnections;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** No server restart or external HTTP: mutations enqueue desired state in the existing database. */
final class RouteOperationsCommand {
    private RouteOperationsCommand() {
    }

    static Object run(CliArguments args) {
        Set<String> allowed = new HashSet<>(Set.of("config", "route-id"));
        if (args.command().equals("routes-list")) {
            allowed.remove("route-id");
        }
        if (!Set.of("routes-list", "routes-status", "routes-preview").contains(args.command())) {
            allowed.addAll(Set.of("approval", "request-id", "actor"));
            if (!args.command().equals("routes-register")) {
                allowed.addAll(Set.of("expected-revision", "reason", "message", "source-url"));
            }
            if (Set.of("routes-collection", "routes-service").contains(args.command())) {
                allowed.add("enabled");
            }
        }
        args.requireOnlyOptions(allowed);
        DatabaseEnvironment environment =
            DatabaseEnvironment.load(Configuration.load(args.requiredPath("config")));
        final boolean read =
            Set.of("routes-list", "routes-status", "routes-preview").contains(args.command());
        if (!read && environment.targetKind() != DatabaseEnvironment.TargetKind.LOCAL) {
            new ApprovalGate(Clock.systemUTC())
                .require(
                    args.requiredPath("approval"),
                    "ACADEMY_ROUTE_OPERATIONS",
                    null,
                    environment.identitySha256());
        }
        DataSource dataSource = DatabaseConnections.dataSource(environment);
        RouteOperations operations = RouteOperationsCommandConfiguration.create(dataSource);
        try {
            if (args.command().equals("routes-list")) {
                return operations.list();
            }
            String routeId = args.required("route-id");
            RouteOperation.requireRouteId(routeId);
            if (read) {
                return Map.of(
                    "target",
                    environment.targetKind(),
                    "databaseIdentity",
                    environment.identitySha256(),
                    "preview",
                    operations.preview(routeId),
                    "note",
                    "requested state is not worker application acknowledgement");
            }
            UUID request = UUID.fromString(args.required("request-id"));
            String actor = args.required("actor");
            if (args.command().equals("routes-register")) {
                return operations.register(routeId, request, actor);
            }
            String action =
                switch (args.command()) {
                    case "routes-collection" -> "COLLECTION";
                    case "routes-service" -> "SERVICE";
                    case "routes-disable-both" -> "DISABLE_BOTH";
                    case "routes-retry" -> "RETRY";
                    case "routes-notice" -> "NOTICE";
                    case "routes-retry-geometry" -> "RETRY_GEOMETRY";
                    default -> throw new MaintenanceException("UNKNOWN_COMMAND");
                };
            boolean enabled = false;
            if (action.equals("COLLECTION") || action.equals("SERVICE")) {
                String raw = args.required("enabled");
                if (!raw.equals("true") && !raw.equals("false")) {
                    throw new MaintenanceException("INVALID_ENABLED");
                }
                enabled = Boolean.parseBoolean(raw);
            }
            return operations.change(
                routeId,
                Long.parseLong(args.required("expected-revision")),
                request,
                actor,
                action,
                enabled,
                args.required("reason"),
                args.required("message"),
                args.optional("source-url", ""));
        } catch (IllegalArgumentException e) {
            String code = e.getMessage();
            if (code != null && code.matches("[A-Z_]+")) {
                throw new MaintenanceException(code, e);
            }
            throw new MaintenanceException("INVALID_ROUTE_OPERATION_ARGUMENTS", e);
        }
    }
}
