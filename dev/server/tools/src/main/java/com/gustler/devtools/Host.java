package com.gustler.devtools;

import tools.jackson.databind.JsonNode;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

class Host {
    record Account(int uid, int gid, String shell) {}

    record Result(int exit, String stdout, String stderr) {
        String checked() {
            Checks.require(exit == 0, "DEV_COMMAND_FAILED");
            return stdout;
        }
    }

    Result run(Path cwd, Map<String, String> environment, Duration timeout, List<String> command)
            throws Exception {
        return new CommandRunner().run(cwd, environment, timeout, command);
    }

    String run(String... command) throws Exception {
        return run(Path.of("/"), null, Duration.ofSeconds(30), List.of(command)).checked();
    }

    boolean root() throws Exception {
        return run("id", "-u").strip().equals("0");
    }

    Account account() throws Exception {
        for (var line : Files.readAllLines(Path.of("/etc/passwd"))) {
            var fields = line.split(":", -1);
            if (fields.length == 7 && fields[0].equals("salmonbus")) {
                return new Account(
                        Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), fields[6]);
            }
        }
        throw new IllegalArgumentException("DEV_SERVICE_ACCOUNT_MISSING");
    }

    JsonNode identity() throws Exception {
        var client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .proxy(
                                new ProxySelector() {
                                    public List<Proxy> select(URI uri) {
                                        return List.of(Proxy.NO_PROXY);
                                    }

                                    public void connectFailed(
                                            URI uri,
                                            SocketAddress address,
                                            java.io.IOException error) {}
                                })
                        .build();
        var request =
                HttpRequest.newBuilder(URI.create("http://169.254.169.254/latest/api/token"))
                        .timeout(Duration.ofSeconds(3))
                        .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                        .PUT(HttpRequest.BodyPublishers.noBody())
                        .build();
        var token = client.send(request, HttpResponse.BodyHandlers.ofString());
        Checks.require(token.statusCode() == 200 && token.body().length() < 4096, "DEV_IMDS");
        request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://169.254.169.254/latest/dynamic/instance-identity/document"))
                        .timeout(Duration.ofSeconds(3))
                        .header("X-aws-ec2-metadata-token", token.body())
                        .GET()
                        .build();
        var document = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        Checks.require(document.statusCode() == 200 && document.body().length < 16384, "DEV_IMDS");
        return Json.read(document.body());
    }

    JsonNode mounted(Path data) throws Exception {
        Checks.require(!Files.isSymbolicLink(data), "DEV_DATA_MOUNT");
        var filesystems =
                Json.read(
                                run("findmnt", "-J", "-M", data.toString(), "-o", "UUID,FSTYPE")
                                        .getBytes())
                        .path("filesystems");
        Checks.require(
                filesystems.isArray()
                        && filesystems.size() == 1
                        && filesystems.get(0).path("fstype").asText().equals("xfs"),
                "DEV_DATA_MOUNT");
        return filesystems.get(0);
    }

    void verifyInstance(JsonNode identity) throws Exception {
        Checks.require(
                identity.path("region").asText().equals("ap-northeast-2")
                        && identity.path("instanceType").asText().equals("t4g.small"),
                "DEV_INSTANCE");
        var response =
                run(
                                Path.of("/"),
                                null,
                                Duration.ofSeconds(20),
                                List.of(
                                        "aws",
                                        "ec2",
                                        "describe-instances",
                                        "--instance-ids",
                                        identity.path("instanceId").asText(),
                                        "--region",
                                        "ap-northeast-2",
                                        "--output",
                                        "json",
                                        "--no-cli-pager",
                                        "--cli-connect-timeout",
                                        "5",
                                        "--cli-read-timeout",
                                        "10"))
                        .checked();
        verifyTags(identity, Json.read(response.getBytes()));
    }

    static void verifyTags(JsonNode identity, JsonNode response) {
        var instances =
                response.path("Reservations").findValues("Instances").stream()
                        .flatMap(node -> node.valueStream())
                        .toList();
        Checks.require(
                instances.size() == 1
                        && instances
                                .getFirst()
                                .path("InstanceId")
                                .asText()
                                .equals(identity.path("instanceId").asText()),
                "DEV_INSTANCE");
        var tags = new java.util.HashMap<String, String>();
        instances
                .getFirst()
                .path("Tags")
                .forEach(tag -> tags.put(tag.path("Key").asText(), tag.path("Value").asText()));
        Checks.require(
                tags.entrySet()
                        .containsAll(
                                Map.of(
                                                "Name",
                                                "salmonbus-backend-dev",
                                                "Environment",
                                                "dev",
                                                "ProjectTeam",
                                                "salmonbus",
                                                "Service",
                                                "techcourse",
                                                "Role",
                                                "techcourse-etc")
                                        .entrySet()),
                "DEV_INSTANCE_TAGS");
    }
}
