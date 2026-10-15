#!/bin/sh
# Isolated upstream -> hardened image compatibility test. Never uses Compose volumes.
set -eu

pg_smoke_upstream='mirror.gcr.io/library/postgres:18.6-alpine3.24@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873'
pg_smoke_image="${POSTGRES_IMAGE:-changuard-postgres:18.6-security.1}"
pg_smoke_suffix="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
pg_smoke_container="changeguard-postgres-smoke-${pg_smoke_suffix}"
pg_smoke_volume="changeguard-postgres-smoke-data-${pg_smoke_suffix}"

cleanup() {
    docker rm --force "$pg_smoke_container" >/dev/null 2>&1 || true
    docker volume rm "$pg_smoke_volume" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 1' INT TERM

start_database() {
    pg_smoke_start_image="$1"
    shift
    docker run --detach --name "$pg_smoke_container" \
        --env POSTGRES_USER=changeguard_smoke --env POSTGRES_DB=changeguard_smoke \
        --env POSTGRES_PASSWORD=changeguard-smoke \
        --mount "type=volume,source=${pg_smoke_volume},target=/var/lib/postgresql" \
        "$@" "$pg_smoke_start_image" >/dev/null
    pg_smoke_attempt=0
    until docker exec "$pg_smoke_container" pg_isready --host 127.0.0.1 \
            --username changeguard_smoke --dbname changeguard_smoke >/dev/null 2>&1; do
        pg_smoke_attempt=$((pg_smoke_attempt + 1))
        if [ "$pg_smoke_attempt" -ge 60 ]; then
            docker logs "$pg_smoke_container"
            exit 1
        fi
        sleep 1
    done
    docker exec "$pg_smoke_container" sh -c \
        'test "$(stat -c %u /proc/1)" = "$(id -u postgres)"'
}

stop_database() {
    docker stop --time 30 "$pg_smoke_container" >/dev/null
    docker rm "$pg_smoke_container" >/dev/null
}

docker volume create "$pg_smoke_volume" >/dev/null
start_database "$pg_smoke_upstream"
docker exec "$pg_smoke_container" psql --username changeguard_smoke --dbname changeguard_smoke \
    --set ON_ERROR_STOP=1 --command 'CREATE TABLE storage_smoke (value INTEGER NOT NULL); INSERT INTO storage_smoke VALUES (42);' >/dev/null
stop_database

# The normal non-root startup must read an existing upstream-initialized volume.
start_database "$pg_smoke_image"
test "$(docker exec "$pg_smoke_container" psql --username changeguard_smoke --dbname changeguard_smoke \
    --tuples-only --no-align --set ON_ERROR_STOP=1 --command 'SELECT value FROM storage_smoke;')" = 42
stop_database

# Explicit root startup must still drop privileges through the patched helper.
start_database "$pg_smoke_image" --user root
test "$(docker exec "$pg_smoke_container" psql --username changeguard_smoke --dbname changeguard_smoke \
    --tuples-only --no-align --set ON_ERROR_STOP=1 --command 'SELECT value FROM storage_smoke;')" = 42
echo 'PostgreSQL smoke test passed: retained data and non-root server in both startup modes.'
