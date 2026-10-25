# syntax=mirror.gcr.io/docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
FROM mirror.gcr.io/library/node:24.21.0-alpine3.24@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS dependencies

WORKDIR /app
RUN chown node:node /app
USER node
COPY --chown=node:node package.json package-lock.json ./
RUN npm ci

# Local Compose selects this target explicitly; the default image serves compiled assets.
FROM dependencies AS development
COPY --chown=node:node . .
EXPOSE 5173
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:5173/ || exit 1
CMD ["node", "node_modules/vite/bin/vite.js", "--host", "0.0.0.0"]

# Only public configuration is compiled in. Private .env files are excluded by .dockerignore.
FROM dependencies AS build
ARG VITE_ORGANIZATION_ID=local
ENV VITE_API_BASE_URL=/api VITE_ORGANIZATION_ID=${VITE_ORGANIZATION_ID}
COPY --chown=node:node . .
RUN npm run build

# The upstream Caddy image bundles vulnerable Go/x-net versions. Build its pinned source
# with the patched toolchain and HTTP/2 library; neither Go nor its cache enters the runtime.
FROM mirror.gcr.io/library/golang:1.26.10-alpine3.24@sha256:27f2d3b83cfc5803c8078f8e730af0f7c431bf067afaec8f60a36fb763187cba AS proxy-build
WORKDIR /build
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOMAXPROCS=2
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go mod init com.changeguard/visual-proxy \
    && go get github.com/caddyserver/caddy/v2/cmd/caddy@v2.11.7 golang.org/x/net@v0.60.0 \
    && go build -mod=readonly -trimpath -ldflags="-s -w" -o /out/caddy github.com/caddyserver/caddy/v2/cmd/caddy

# One small runtime serves the SPA, proxies API requests, and manages HTTPS on EC2.
FROM mirror.gcr.io/library/alpine:3.24.2@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6 AS runtime
ENV XDG_DATA_HOME=/data XDG_CONFIG_HOME=/config
# Match the backend's musl regcomp backport (CVE-2026-107891), keeping libc utilities aligned.
RUN apk add --no-cache ca-certificates musl=1.2.6-r3 musl-utils=1.2.6-r3 \
    && addgroup -S -g 10001 changeguard \
    && adduser -S -D -H -u 10001 -G changeguard changeguard \
    && mkdir -p /data /config /srv /etc/caddy \
    && chown -R 10001:10001 /data /config
COPY --from=proxy-build /out/caddy /usr/bin/caddy
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
USER 10001:10001
# Host ports 80/443 map to these unprivileged container ports in the EC2 stack.
EXPOSE 8080 8443
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q --spider http://127.0.0.1:9090/healthz || exit 1
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
