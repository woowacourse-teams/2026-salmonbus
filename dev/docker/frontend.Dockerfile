FROM node:24-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20

RUN corepack enable && corepack prepare pnpm@11.20.0 --activate
WORKDIR /workspace/frontend
COPY --chown=node:node frontend/ ./
RUN chown -R node:node /workspace/frontend
USER node
RUN pnpm install --frozen-lockfile
CMD ["pnpm", "exec", "webpack", "serve", "--config", "webpack/webpack.local.cjs"]
