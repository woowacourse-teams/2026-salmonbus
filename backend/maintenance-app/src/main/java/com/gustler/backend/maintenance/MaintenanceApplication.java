package com.gustler.backend.maintenance;

import com.gustler.backend.forecasting.api.model.InspectModelBundle;
import com.gustler.backend.forecasting.api.model.InspectModelBundle.Inspection;
import com.gustler.backend.forecasting.api.model.ModelLoadException;
import com.gustler.backend.forecasting.api.quality.GetTripQualityStatus;
import com.gustler.backend.forecasting.api.quality.PreviewTripQuality;
import com.gustler.backend.forecasting.api.quality.ProcessTripQualityChunk;
import com.gustler.backend.forecasting.api.quality.TripQualityChunkResult;
import com.gustler.backend.forecasting.api.quality.TripQualityPreview;
import com.gustler.backend.forecasting.api.quality.TripQualityStatus;
import com.gustler.backend.maintenance.DatabaseEnvironment.TargetKind;
import com.gustler.backend.maintenance.configuration.BundleInspectionCommandConfiguration;
import com.gustler.backend.maintenance.configuration.MaintenanceConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 유지보수 명령을 선택해 실행하고 정해진 JSON 결과와 종료 코드를 출력한다.
 * 예외 원문과 인증정보는 표준 오류에 노출하지 않는다.
 */
public final class MaintenanceApplication {
    private MaintenanceApplication() {
    }

    /**
     * 명령 결과를 정규 JSON으로 출력하고 성공은 0, 유지보수 실패는 1로 프로세스를 종료한다.
     */
    public static void main(String[] rawArguments) {
        final int exit = execute(rawArguments);
        System.exit(exit);
    }

    private static int execute(String[] rawArguments) {
        try {
            Object result = run(CliArguments.parse(rawArguments));
            System.out.println(CanonicalJson.stringOf(Map.of("status", "succeeded", "result", result)));
            return 0;
        } catch (MaintenanceException e) {
            System.err.println(CanonicalJson.stringOf(Map.of("status", "failed", "code", e.code())));
            return 1;
        } catch (RuntimeException e) {
            System.err.println(CanonicalJson.stringOf(
                Map.of("status", "failed", "code", "UNEXPECTED_SANITIZED_FAILURE")));
            return 1;
        }
    }

    static Object run(CliArguments arguments) {
        return switch (arguments.command()) {
            case "bundle-check" -> bundleCheck(arguments);
            case "routes-list",
                    "routes-status",
                    "routes-register",
                    "routes-collection",
                    "routes-service",
                    "routes-disable-both",
                    "routes-retry",
                    "routes-preview",
                    "routes-notice",
                    "routes-retry-geometry" ->
                RouteOperationsCommand.run(arguments);
            case "quality-preview", "quality-rebuild", "quality-status" -> quality(arguments);
            default -> throw new MaintenanceException("UNKNOWN_COMMAND");
        };
    }

    private static Object bundleCheck(CliArguments arguments) {
        String directory = arguments.requiredPath("directory").toString();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(BundleInspectionCommandConfiguration.class);
            context.refresh();
            Inspection inspected = context.getBean(InspectModelBundle.class).inspect(directory);
            return Map.of(
                "validation", "PASSED",
                "activated", false,
                "accuracyEvaluated", false,
                "releaseId", inspected.releaseId(),
                "bundleDigest", inspected.bundleDigest(),
                "featureContractVersion", inspected.featureContractVersion(),
                "dataThrough", inspected.dataThrough(),
                "routes", inspected.routes());
        } catch (ModelLoadException error) {
            Matcher code = Pattern.compile("^\\[([A-Z][A-Z0-9_]*)\\]")
                .matcher(modelFailureMessage(error));
            throw new MaintenanceException(
                bundleRejectionCode(code), error);
        }
    }

    private static Object quality(CliArguments arguments) {
        Configuration
        configuration = Configuration.load(arguments.requiredPath("config"));
        DatabaseEnvironment
        environment = DatabaseEnvironment.load(configuration);
        final long version = Long.parseLong(arguments.required("route-version"));
        final boolean write = arguments.command().equals("quality-rebuild");
        if (write && environment.targetKind() != TargetKind.LOCAL) {
            new ApprovalGate(Clock.systemUTC()).require(arguments.requiredPath("approval"),
                ApprovalGate.ACADEMY_TRIP_QUALITY_REBUILD, null, environment.identitySha256());
        }
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatabaseEnvironment.class, () -> environment);
            context.register(MaintenanceConfiguration.class);
            context.refresh();
            if (arguments.command().equals("quality-status")) {
                return context.getBean(GetTripQualityStatus.class)
                    .status(version)
                    .stream()
                    .map(MaintenanceApplication::statusOutput)
                    .toList();
            }
            Instant until = Instant.parse(arguments.required("until"));
            if (write) {
                TripQualityChunkResult result = context.getBean(ProcessTripQualityChunk.class)
                    .applyChunk(version, until, Integer.parseInt(arguments.required("batch-limit")));
                return Map.of("processedBatches", result.processedBatches(),
                    "discoveryCompleted", result.discoveryCompleted(), "completed", result.completed());
            }
            TripQualityPreview result = context.getBean(PreviewTripQuality.class).preview(version, until);
            return Map.of("scope", result.scope(), "sampled_batches", result.sampledBatches(),
                "sampled_observations", result.sampledObservations(), "sampled_above_range",
                result.sampledAboveRange());
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
        result.put("last_batch_at", dateOf(status.lastBatchAt()));
        result.put("last_batch_id", status.lastBatchId());
        result.put("can_release", status.canRelease());
        result.put("started_at", dateOf(status.startedAt()));
        result.put("investigated_at", dateOf(status.investigatedAt()));
        return result;
    }

    private static String modelFailureMessage(ModelLoadException error) {
        if (error.getMessage() == null) {
            return "";
        }
        return error.getMessage();
    }

    private static String bundleRejectionCode(Matcher code) {
        if (code.find()) {
            return "BUNDLE_REJECTED_" + code.group(1);
        }
        return "BUNDLE_REJECTED";
    }

    private static Date dateOf(Instant timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Date.from(timestamp);
    }
}
