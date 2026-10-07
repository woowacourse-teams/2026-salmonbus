FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS build
WORKDIR /build/extension
COPY dev/wiremock-extension/ ./
COPY dev/data/routes.json /build/data/routes.json
COPY dev/data/catalog-routes.json /build/data/catalog-routes.json
RUN ./gradlew test jar --no-daemon --console=plain

FROM wiremock/wiremock:3.13.2@sha256:0d4ecb3e4dc8213fd7a4d37d6a78f6e6b553a6d2e15bd51b0999781282ac61b3
COPY --from=build /build/extension/build/libs/local-gbis-replay.jar /var/wiremock/extensions/
COPY dev/wiremock/mappings/ /home/wiremock/mappings/
COPY dev/data/routes.json /local/data/routes.json
COPY dev/data/catalog-routes.json /local/data/catalog-routes.json
COPY dev/scenarios/replay.json /local/scenarios/replay.json
ENTRYPOINT ["/docker-entrypoint.sh", "--extensions", "com.gustler.localgbis.GbisReplayTransformer", "--max-request-journal-entries", "500", "--disable-banner", "--disable-request-logging"]
