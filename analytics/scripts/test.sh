#!/usr/bin/env bash
# Runs the whole pytest suite in the test image against a THROWAWAY PostgreSQL 18 with the backend's Flyway
# migrations applied. Everything it starts is removed on exit. Never touches the developer database.
#
#   analytics/scripts/test.sh            # from the yousong-backend repository root
#   analytics/scripts/test.sh -k e2e     # extra arguments go to pytest
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo="$(cd "$here/.." && pwd)"
run_id="yousong-analytics-test-$$-$RANDOM"
network="$run_id-net"
database="$run_id-pg"
pgword=$(od -An -N16 -tx1 /dev/urandom | tr -d " \n")  # random hex, unquoted on purpose

# A free local port (never 5432, 8080, 9011 or 3000).
port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
case "$port" in 5432|8080|9011|3000) echo "Unlucky port $port, run again" >&2; exit 1 ;; esac

cleanup() {
  docker rm -f -v "$database" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker build --quiet --target test -t yousong-analytics:test "$here" >/dev/null
docker network create "$network" >/dev/null
docker run -d --rm --name "$database" --network "$network" -p "127.0.0.1:$port:5432" \
  -e POSTGRES_DB=analytics_test -e POSTGRES_USER=analytics_test -e POSTGRES_PASSWORD=$pgword \
  -e TZ=UTC postgres:18 >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$database" pg_isready -q -h 127.0.0.1 -U analytics_test -d analytics_test; then break; fi
  sleep 1
done
echo "Throwaway PostgreSQL $database on 127.0.0.1:$port"

docker run --rm --network "$network" \
  -v "$repo/src/main/resources/db/migration:/migrations:ro" \
  -e ANALYTICS_TEST_DB_URL="postgresql://$database:5432/analytics_test" \
  -e ANALYTICS_TEST_DB_USER=analytics_test -e ANALYTICS_TEST_DB_PASSWORD=$pgword \
  -e ANALYTICS_TEST_MIGRATIONS=/migrations \
  yousong-analytics:test -q -p no:cacheprovider "$@"
