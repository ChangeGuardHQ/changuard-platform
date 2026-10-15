# syntax=mirror.gcr.io/docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
FROM mirror.gcr.io/library/postgres:18.6-alpine3.24@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873

LABEL org.opencontainers.image.title="ChangeGuard PostgreSQL" \
      org.opencontainers.image.version="18.6-security.1"

# Upstream gosu 1.19 embeds Go 1.24.6. Use Alpine's native privilege helper
# for the entrypoint's sole gosu call, preserving exec and signal behavior.
RUN apk add --no-cache su-exec=0.3-r0 \
    && grep -q 'exec gosu postgres ' /usr/local/bin/docker-entrypoint.sh \
    && sed -i 's/exec gosu postgres /exec su-exec postgres /' /usr/local/bin/docker-entrypoint.sh \
    && ! grep -wq gosu /usr/local/bin/docker-entrypoint.sh \
    && rm -f /usr/local/bin/gosu \
    && test "$(su-exec postgres id -u)" = "$(id -u postgres)"

# PostgreSQL 18's writable storage parent allows initialization as postgres.
# The helper remains available for an explicit root startup with an existing volume.
USER postgres
HEALTHCHECK --interval=5s --timeout=5s --start-period=15s --retries=10 \
    CMD pg_isready -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-postgres}" || exit 1
