# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS dependencies

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 developer
WORKDIR /workspace/backend
RUN chown developer:developer /workspace/backend \
    && mkdir -p /local/models && chown developer:developer /local/models
ENV GRADLE_USER_HOME=/home/developer/.gradle
COPY --chown=developer:developer backend/gradlew backend/build.gradle backend/settings.gradle ./
COPY --chown=developer:developer backend/gradle/ ./gradle/
COPY --chown=developer:developer backend/api-app/build.gradle ./api-app/build.gradle
COPY --chown=developer:developer backend/worker-app/build.gradle ./worker-app/build.gradle
COPY --chown=developer:developer backend/maintenance-app/build.gradle ./maintenance-app/build.gradle
COPY --chown=developer:developer backend/common/build.gradle ./common/build.gradle
COPY --chown=developer:developer backend/business/route-catalog/build.gradle ./business/route-catalog/build.gradle
COPY --chown=developer:developer backend/business/observations/build.gradle ./business/observations/build.gradle
COPY --chown=developer:developer backend/business/forecasting/build.gradle ./business/forecasting/build.gradle
COPY --chown=developer:developer backend/business/api-call-quota/build.gradle ./business/api-call-quota/build.gradle
COPY --chown=developer:developer backend/integrations/gbis-client/build.gradle ./integrations/gbis-client/build.gradle
COPY --chown=developer:developer backend/libraries/operation-log/build.gradle ./libraries/operation-log/build.gradle
COPY --chown=developer:developer dev/docker/dependencies.init dev/docker/gradle.properties /local-build/
USER developer
ARG TARGETARCH
RUN --mount=type=cache,id=salmonbus-backend-gradle-${TARGETARCH},target=/dependency-cache,uid=10001,gid=10001,sharing=locked \
    cp /local-build/gradle.properties /dependency-cache/gradle.properties \
    && GRADLE_USER_HOME=/dependency-cache ./gradlew --init-script /local-build/dependencies.init \
        prepareLocalDependencies --no-daemon --console=plain \
    && mkdir -p "$GRADLE_USER_HOME" \
    && cp -a /dependency-cache/wrapper /dependency-cache/caches "$GRADLE_USER_HOME/" \
    && cp /local-build/gradle.properties "$GRADLE_USER_HOME/gradle.properties" \
    && find "$GRADLE_USER_HOME" -type f \( -name '*.lock' -o -name '*.lck' -o -name gc.properties \) -delete

FROM dependencies AS development
COPY --chown=developer:developer backend/ ./
COPY --chown=developer:developer dev/data/ /local/data/
COPY --chmod=755 dev/docker/run-backend.sh /usr/local/bin/run-backend
ARG DEV_COMPONENT=api-app
RUN case "$DEV_COMPONENT" in api-app|worker-app) ;; *) exit 1 ;; esac \
    && ./gradlew ":${DEV_COMPONENT}:classes" --offline --no-daemon --console=plain
RUN if [ "$DEV_COMPONENT" = worker-app ]; then \
        ./gradlew --init-script /local/data/local-data.init :worker-app:localDataClasses --offline --no-daemon --console=plain; \
    fi
ENTRYPOINT ["run-backend"]
