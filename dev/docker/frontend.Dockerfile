# syntax=docker/dockerfile:1
FROM node:24-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20

RUN corepack enable && corepack prepare pnpm@11.20.0 --activate
WORKDIR /workspace/frontend
COPY --chown=node:node frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN chown -R node:node /workspace/frontend
USER node
ARG TARGETARCH
RUN --mount=type=cache,id=salmonbus-pnpm-${TARGETARCH},target=/pnpm/cache,uid=1000,gid=1000,sharing=locked \
    pnpm install --frozen-lockfile --store-dir /pnpm/cache
COPY --chown=node:node frontend/ ./
ENV NODE_OPTIONS=--max-old-space-size=512
CMD ["pnpm", "exec", "webpack", "serve", "--config", "webpack/webpack.local.cjs"]
