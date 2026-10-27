# Claude Auto bridge for a Raspberry Pi (64-bit OS), a home server or any Docker host.
#   docker run -d --name claude-auto --network host -v claude-auto:/home/node \
#     -v ~/projects:/home/node/projects ghcr.io/psicrone/claude-auto-bridge
# See install/raspberry-pi.sh for the guided setup.

# TypeScript compiles the same everywhere, so build it once on the build machine.
FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:22-bookworm-slim
# Tools Claude Code uses on your projects.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git openssh-client procps ripgrep \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json ./
# Installs the Claude Code binary for this image's architecture (it ships inside the Agent SDK).
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY --from=build /src/dist ./dist
RUN rm -rf dist/test && chmod 755 dist/server.js && ln -s /app/dist/server.js /usr/local/bin/claude-auto-bridge

USER node
WORKDIR /home/node
RUN mkdir -p projects .claude-auto-bridge
ENV NODE_ENV=production \
    BRIDGE_HOST=0.0.0.0 \
    BRIDGE_PORT=8787 \
    BRIDGE_PROJECTS=projects=/home/node/projects \
    BRIDGE_PERMISSIONS=ask
EXPOSE 8787
HEALTHCHECK --interval=1m --timeout=5s CMD curl -fsk "https://127.0.0.1:${BRIDGE_PORT}/health" || curl -fs "http://127.0.0.1:${BRIDGE_PORT}/health"
CMD ["claude-auto-bridge"]
