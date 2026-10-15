package com.gustler.devtools;

import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** The optional RDS target is recorded only after checking the dev instance in AWS. */
final class DatabaseTarget {
    static final String FILE = "database-target.json";
    static final String CERTIFICATE = "rds-ca.pem";
    static final String CERTIFICATE_PATH = "/etc/salmonbus-dev/" + CERTIFICATE;
    private static final String IDENTIFIER = "salmonbus-dev-db";
    private static final String REGION = "ap-northeast-2";
    private static final Set<String> FIELDS =
            Set.of(
                    "environment",
                    "dbInstanceIdentifier",
                    "dbInstanceArn",
                    "endpoint",
                    "port",
                    "certificateSha256");

    private DatabaseTarget() {}

    static String url(Path directory) throws Exception {
        var target = directory.resolve(FILE);
        if (!FileOps.exists(target)) {
            return Environment.DATABASE;
        }
        Checks.require(Files.size(target) <= 8192, "DEV_RDS_TARGET");
        var value = Json.read(FileOps.privateText(target).getBytes());
        var marker =
                Json.read(FileOps.privateText(directory.resolve("environment.json")).getBytes());
        var certificate = directory.resolve(CERTIFICATE);
        FileOps.protectedFile(certificate, "DEV_RDS_CERTIFICATE");
        Checks.require(
                (FileOps.attribute(certificate, "mode") & 0777) == 0644,
                "DEV_RDS_CERTIFICATE");
        Checks.require(Files.size(certificate) <= 524288, "DEV_RDS_CERTIFICATE");
        return validatedUrl(value, marker, FileOps.sha(certificate));
    }

    static String validatedUrl(JsonNode value, JsonNode marker, String certificateDigest) {
        Checks.require(
                value.isObject()
                        && value.propertyStream()
                                .map(Map.Entry::getKey)
                                .collect(java.util.stream.Collectors.toSet())
                                .equals(FIELDS)
                        && "dev".equals(value.path("environment").asText())
                        && "dev".equals(marker.path("environment").asText())
                        && IDENTIFIER.equals(value.path("dbInstanceIdentifier").asText())
                        && marker.path("accountId").asText().matches("[0-9]{12}")
                        && ("arn:aws:rds:" + REGION + ":"
                                        + marker.path("accountId").asText() + ":db:" + IDENTIFIER)
                                .equals(value.path("dbInstanceArn").asText())
                        && value.path("endpoint").asText().matches(
                                IDENTIFIER + "\\.[a-z0-9]+\\." + REGION
                                        + "\\.rds\\.amazonaws\\.com")
                        && value.path("port").isIntegralNumber()
                        && value.path("port").asInt() == 5432,
                "DEV_RDS_TARGET");
        Checks.require(
                certificateDigest != null
                        && certificateDigest.matches("[0-9a-f]{64}")
                        && certificateDigest.equals(value.path("certificateSha256").asText()),
                "DEV_RDS_CERTIFICATE");
        return "jdbc:postgresql://" + value.path("endpoint").asText()
                + ":5432/salmonbus_dev?sslmode=verify-full&sslrootcert=" + CERTIFICATE_PATH;
    }

    static void preserve(Path from, Path to, SettingsPreparation.Writer writer) throws Exception {
        if (!FileOps.exists(from.resolve(FILE))) {
            return;
        }
        url(from);
        writer.write(to.resolve(FILE), FileOps.privateText(from.resolve(FILE)).getBytes(), 0600);
        writer.write(to.resolve(CERTIFICATE), Files.readAllBytes(from.resolve(CERTIFICATE)), 0644);
    }
}
