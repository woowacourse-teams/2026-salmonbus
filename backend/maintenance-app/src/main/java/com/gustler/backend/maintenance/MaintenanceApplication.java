package com.gustler.backend.maintenance;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.api.evaluation.AlightingAwareEvaluationArchive;
import com.gustler.backend.forecasting.api.alighting.BootstrapAlightingAwareHistory;
import com.gustler.backend.forecasting.api.alighting.AlightingAwareHistoryBootstrapException;
import com.gustler.backend.forecasting.api.model.InspectModelBundle;
import com.gustler.backend.forecasting.api.model.ModelLoadException;
import com.gustler.backend.maintenance.configuration.BundleInspectionCommandConfiguration;
import com.gustler.backend.maintenance.configuration.AlightingAwareEvaluationRestoreCommandConfiguration;
import com.gustler.backend.maintenance.configuration.AlightingAwareHistoryBootstrapCommandConfiguration;
import java.time.Clock;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 유지보수 명령을 선택해 실행하고 정해진 JSON 결과와 종료 코드를 출력한다. 예외 원문과 인증정보는 표준 오류에 노출하지 않는다.
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
            System.err
                .println(CanonicalJson.stringOf(Map.of("status", "failed", "code", "UNEXPECTED_SANITIZED_FAILURE")));
            return 1;
        }
    }

    static Object run(CliArguments arguments) {
        return switch (arguments.command()) {
        case "bundle-check" -> bundleCheck(arguments);
        case "route-display-export", "route-display-status", "route-display-import", "route-display-disable" ->
            RouteDisplayCommand.run(arguments);
        case "route-display-retry" -> RouteDisplayRetryCommand.run(arguments);
        case "alighting-history-bootstrap" -> alightingHistoryBootstrap(arguments);
        case "alighting-evaluation-restore" -> alightingEvaluationRestore(arguments);
        default -> throw new MaintenanceException("UNKNOWN_COMMAND");
        };
    }

    private static Object bundleCheck(CliArguments arguments) {
        String directory = arguments.requiredPath("directory").toString();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(BundleInspectionCommandConfiguration.class);
            context.refresh();
            InspectModelBundle.Inspection inspected = context.getBean(InspectModelBundle.class).inspect(directory);
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
            Matcher code = Pattern.compile("^\\[([A-Z][A-Z0-9_]*)\\]").matcher(modelFailureMessage(error));
            throw new MaintenanceException(bundleRejectionCode(code), error);
        }
    }

    private static Object alightingHistoryBootstrap(CliArguments arguments) {
        AlightingAwareHistoryBootstrapCommand.Options options = AlightingAwareHistoryBootstrapCommand.parse(arguments);
        Configuration configuration = Configuration.load(options.config());
        DatabaseEnvironment environment = DatabaseEnvironment.load(configuration);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatabaseEnvironment.class, () -> environment);
            context.register(AlightingAwareHistoryBootstrapCommandConfiguration.class);
            context.refresh();
            return AlightingAwareHistoryBootstrapCommand.invoke(options, context.getBean(BootstrapAlightingAwareHistory.class));
        } catch (AlightingAwareHistoryBootstrapException failure) {
            throw new MaintenanceException(failure.code(), failure);
        } catch (CannotCreateTransactionException failure) {
            throw new MaintenanceException("DATABASE_CONNECTION_FAILED", failure);
        } catch (RuntimeException failure) {
            throw new MaintenanceException("DATABASE_TRANSACTION_FAILED", failure);
        }
    }

    private static Object alightingEvaluationRestore(CliArguments arguments) {
        AlightingAwareEvaluationRestoreCommand.Options options = AlightingAwareEvaluationRestoreCommand.parse(arguments);
        LocalAlightingAwareArchive archive = LocalAlightingAwareArchive.read(options.archiveFile());
        DatabaseEnvironment environment = DatabaseEnvironment.load(Configuration.load(options.config()));
        AlightingAwareEvaluationRestoreCommand.authorize(arguments, environment, archive, Clock.systemUTC());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatabaseEnvironment.class, () -> environment);
            context.registerBean(EvaluationArchiveObjects.class, () -> archive);
            context.register(AlightingAwareEvaluationRestoreCommandConfiguration.class);
            context.refresh();
            return AlightingAwareEvaluationRestoreCommand.invoke(options, context.getBean(AlightingAwareEvaluationArchive.class));
        } catch (MaintenanceException failure) {
            throw failure;
        } catch (CannotCreateTransactionException failure) {
            throw new MaintenanceException("DATABASE_CONNECTION_FAILED", failure);
        } catch (RuntimeException failure) {
            throw new MaintenanceException("ALIGHTING_AWARE_EVALUATION_RESTORE_FAILED", failure);
        }
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
}
