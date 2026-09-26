package com.gustler.backend.maintenance;

import com.gustler.backend.forecasting.api.quality.GetTripQualityStatus;
import com.gustler.backend.forecasting.api.quality.PreviewTripQuality;
import com.gustler.backend.forecasting.api.quality.ProcessTripQualityChunk;
import com.gustler.backend.forecasting.api.quality.TripQualityChunkResult;
import com.gustler.backend.forecasting.api.quality.TripQualityPreview;
import com.gustler.backend.forecasting.api.quality.TripQualityStatus;
import com.gustler.backend.maintenance.configuration.MaintenanceConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.CannotCreateTransactionException;

public final class MaintenanceApplication {

    private MaintenanceApplication() {
    }

    public static void main(String[] rawArguments) {
        int exit;
        try {
            Object result = run(CliArguments.parse(rawArguments));
            System.out.println(CanonicalJson.stringOf(Map.of("status", "succeeded", "result", result)));
            exit = 0;
        } catch (MaintenanceException e) {
            System.err.println(CanonicalJson.stringOf(Map.of("status", "failed", "code", e.code())));
            exit = 1;
        } catch (RuntimeException e) {
            System.err.println(CanonicalJson.stringOf(
                Map.of("status", "failed", "code", "UNEXPECTED_SANITIZED_FAILURE")));
            exit = 1;
        }
        System.exit(exit);
    }

    static Object run(CliArguments arguments) {
        return switch (arguments.command()) {
            case "quality-preview", "quality-rebuild", "quality-status" -> quality(arguments);
            default -> throw new MaintenanceException("UNKNOWN_COMMAND");
        };
    }

    private static Object quality(CliArguments arguments) {
        Configuration configuration = Configuration.load(arguments.requiredPath("config"));
        DatabaseEnvironment environment = DatabaseEnvironment.load(configuration);
        long version = Long.parseLong(arguments.required("route-version"));
        boolean write = arguments.command().equals("quality-rebuild");
        if (write && environment.targetKind() != DatabaseEnvironment.TargetKind.LOCAL) {
            new ApprovalGate(Clock.systemUTC()).require(arguments.requiredPath("approval"),
                ApprovalGate.ACADEMY_TRIP_QUALITY_REBUILD, null, environment.identitySha256());
        }
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatabaseEnvironment.class, () -> environment);
            context.register(MaintenanceConfiguration.class);
            context.refresh();
            if (arguments.command().equals("quality-status")) {
                return context.getBean(GetTripQualityStatus.class).status(version).stream()
                    .map(MaintenanceApplication::statusOutput).toList();
            }
            Instant until = Instant.parse(arguments.required("until"));
            if (write) {
                TripQualityChunkResult result = context.getBean(ProcessTripQualityChunk.class)
                    .applyChunk(version, until, Integer.parseInt(arguments.required("batch-limit")));
                return Map.of("processedBatches", result.processedBatches(),
                    "discoveryCompleted", result.discoveryCompleted(), "completed", result.completed(),
                    "waitingForObservations", result.waitingForObservations());
            }
            TripQualityPreview result = context.getBean(PreviewTripQuality.class).preview(version, until);
            return Map.of("scope", result.scope(), "sampled_batches", result.sampledBatches(),
                "sampled_observations", result.sampledObservations(), "sampled_above_range", result.sampledAboveRange());
        } catch (MaintenanceException e) {
            throw e;
        } catch (CannotCreateTransactionException e) {
            throw new MaintenanceException("DATABASE_CONNECTION_FAILED", e);
        } catch (RuntimeException e) {
            throw new MaintenanceException("DATABASE_TRANSACTION_FAILED", e);
        }
    }

    private static Map<String, Object> statusOutput(TripQualityStatus status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vehicle_id", status.vehicleId());
        result.put("phase", status.phase());
        result.put("completed", status.completed());
        result.put("evidence_observation_id", status.evidenceObservationId());
        result.put("last_batch_at", status.lastBatchAt() == null ? null : status.lastBatchAt().atOffset(ZoneOffset.UTC));
        result.put("last_batch_id", status.lastBatchId());
        result.put("can_release", status.canRelease());
        result.put("started_at", status.startedAt() == null ? null : status.startedAt().atOffset(ZoneOffset.UTC));
        result.put("investigated_at", status.investigatedAt() == null ? null : status.investigatedAt().atOffset(ZoneOffset.UTC));
        return result;
    }
}
