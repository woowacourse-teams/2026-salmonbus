FROM eclipse-temurin:21-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 developer
WORKDIR /workspace/backend
COPY --chown=developer:developer backend/ ./
COPY --chmod=755 dev/docker/run-backend.sh /usr/local/bin/run-backend
RUN chown -R developer:developer /workspace/backend
USER developer
ARG DEV_COMPONENT=api-app
RUN case "$DEV_COMPONENT" in api-app|worker-app) ;; *) exit 1 ;; esac \
    && ./gradlew ":${DEV_COMPONENT}:bootJar" --no-daemon --console=plain
ENTRYPOINT ["run-backend"]
