#!/bin/sh

set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$REPOSITORY_ROOT"

normalize_lines() {
  sed '/^$/d' | LC_ALL=C sort | paste -sd ' ' -
}

compose_config() {
  output_option=$1
  shift

  DB_PORT="" \
    GOOGLE_APPLICATION_CREDENTIALS=validation-credentials.json \
    docker compose --env-file /dev/null "$@" config --no-env-resolution "$output_option"
}

compose_service_json_with_db_port() {
  db_port=$1
  service=$2
  shift 2

  DB_PORT="$db_port" \
    GOOGLE_APPLICATION_CREDENTIALS=validation-credentials.json \
    docker compose --env-file /dev/null "$@" \
      config --no-env-resolution --format json "$service"
}

compose_mailpit_service_json() {
  service=$1
  shift

  MAILPIT_SMTP_PORT=11025 \
    MAILPIT_UI_PORT=18025 \
    GOOGLE_APPLICATION_CREDENTIALS=validation-credentials.json \
    docker compose --env-file /dev/null "$@" \
      config --no-env-resolution --format json "$service"
}

assert_service_set() {
  label=$1
  expected_services=$2
  shift 2

  actual=$(compose_config --services "$@" | normalize_lines)
  expected=$(printf '%s\n' $expected_services | normalize_lines)

  if [ "$actual" != "$expected" ]; then
    printf '%s services mismatch\nexpected: %s\nactual:   %s\n' \
      "$label" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_no_profiles() {
  label=$1
  shift

  profiles=$(compose_config --profiles "$@" | normalize_lines)
  if [ -n "$profiles" ]; then
    printf '%s must not declare profiles: %s\n' "$label" "$profiles" >&2
    exit 1
  fi
}

assert_profile_set() {
  label=$1
  expected_profiles=$2
  shift 2

  actual=$(compose_config --profiles "$@" | normalize_lines)
  expected=$(printf '%s\n' $expected_profiles | normalize_lines)

  if [ "$actual" != "$expected" ]; then
    printf '%s profiles mismatch\nexpected: %s\nactual:   %s\n' \
      "$label" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_db_port() {
  label=$1
  db_port=$2
  expected_published_port=$3
  service=$4
  shift 4

  rendered=$(compose_service_json_with_db_port "$db_port" "$service" "$@")

  if ! printf '%s\n' "$rendered" | grep -F "\"target\": 3306" >/dev/null; then
    printf '%s must keep the MySQL container port at 3306\n' "$label" >&2
    exit 1
  fi

  if ! printf '%s\n' "$rendered" |
    grep -F "\"published\": \"$expected_published_port\"" >/dev/null; then
    printf '%s published port mismatch: expected %s\n' \
      "$label" "$expected_published_port" >&2
    exit 1
  fi
}

assert_mailpit_app_context() {
  expected_context=$1
  shift

  rendered=$(PIKU_BACK_APP_BUILD_CONTEXT="$expected_context" \
    compose_mailpit_service_json app "$@")
  printf '%s\n' "$rendered" | python3 -c '
import json
import sys

config = json.load(sys.stdin)
app = config.get("services", {}).get("app", config.get("app", config))
assert app["build"]["context"] == sys.argv[1]
' "$expected_context"
}

validate_core() {
  env_file_reset=$(mktemp)
  trap 'rm -f "$env_file_reset"' EXIT HUP INT TERM
  printf 'services:\n  app:\n    env_file: !reset []\n' > "$env_file_reset"

  dev_files="-f docker-compose.dev.yml -f docker-compose.infra.yml -f $env_file_reset"
  dev_mailpit_files="-f docker-compose.dev.yml -f docker-compose.infra.yml -f docker-compose.mailpit.yml -f $env_file_reset"
  prod_files="-f docker-compose.prod.yml -f docker-compose.infra.yml -f $env_file_reset"

  # Word splitting is intentional so each Compose flag is passed separately.
  # shellcheck disable=SC2086
  compose_config --quiet $dev_files
  # shellcheck disable=SC2086
  assert_service_set "dev + infra" "app db minio redis" $dev_files
  # shellcheck disable=SC2086
  assert_service_set \
    "dev + infra provision" \
    "app db minio minio-provision redis" \
    --profile provision $dev_files
  # shellcheck disable=SC2086
  assert_profile_set "dev + infra" "provision" $dev_files
  # shellcheck disable=SC2086
  assert_db_port "dev default DB port" "" "9915" "db" $dev_files
  # shellcheck disable=SC2086
  assert_db_port "dev custom DB port" "13306" "13306" "db" $dev_files

  # shellcheck disable=SC2086
  compose_config --quiet $dev_mailpit_files
  # shellcheck disable=SC2086
  assert_service_set "dev + infra + Mailpit" "app db mailpit minio redis" $dev_mailpit_files
  # shellcheck disable=SC2086
  assert_service_set \
    "dev + infra + Mailpit provision" \
    "app db mailpit minio minio-provision redis" \
    --profile provision $dev_mailpit_files
  # shellcheck disable=SC2086
  assert_profile_set "dev + infra + Mailpit" "provision" $dev_mailpit_files

  # shellcheck disable=SC2086
  mailpit_app=$(compose_mailpit_service_json app $dev_mailpit_files)
  printf '%s\n' "$mailpit_app" | python3 -c '
import json
import sys

config = json.load(sys.stdin)
app = config.get("services", {}).get("app", config.get("app", config))
env = app["environment"]
assert env["SPRING_MAIL_HOST"] == "mailpit"
assert str(env["SPRING_MAIL_PORT"]) == "1025"
assert env["SPRING_MAIL_USERNAME"] == "signup-test@pikume.test"
assert env["SPRING_MAIL_PASSWORD"] == ""
assert str(env["SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH"]).lower() == "false"
assert str(env["SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE"]).lower() == "false"
assert app["depends_on"]["mailpit"]["condition"] == "service_healthy"
'
  # shellcheck disable=SC2086
  assert_mailpit_app_context "/tmp/piku-back-worktree" $dev_mailpit_files

  # shellcheck disable=SC2086
  mailpit_service=$(compose_mailpit_service_json mailpit $dev_mailpit_files)
  printf '%s\n' "$mailpit_service" | python3 -c '
import json
import sys

config = json.load(sys.stdin)
mailpit = config.get("services", {}).get("mailpit", config.get("mailpit", config))
assert mailpit["image"] == "axllent/mailpit:v1.31.4"
ports = {(item["target"], item["published"], item["host_ip"]) for item in mailpit["ports"]}
assert (1025, "11025", "127.0.0.1") in ports
assert (8025, "18025", "127.0.0.1") in ports
'

  # shellcheck disable=SC2086
  compose_config --quiet $prod_files
  # shellcheck disable=SC2086
  assert_service_set "prod + infra" "app minio prod-db redis" $prod_files
  # shellcheck disable=SC2086
  assert_service_set \
    "prod + infra provision" \
    "app minio minio-provision prod-db redis" \
    --profile provision $prod_files
  # shellcheck disable=SC2086
  assert_profile_set "prod + infra" "provision" $prod_files
  # shellcheck disable=SC2086
  assert_db_port "prod default DB port" "" "9914" "prod-db" $prod_files
  # shellcheck disable=SC2086
  assert_db_port "prod custom DB port" "13306" "13306" "prod-db" $prod_files

  rm -f "$env_file_reset"
  trap - EXIT HUP INT TERM
}

assert_rendered_target() {
  target=$1
  rendered_config=$(mktemp)
  trap 'rm -f "$rendered_config"' EXIT HUP INT TERM

  MONITORING_TARGET="$target" \
    PROMETHEUS_CONFIG_TEMPLATE=monitoring/prometheus/prometheus.yml \
    PROMETHEUS_CONFIG_OUTPUT="$rendered_config" \
    PROMETHEUS_BIN=/usr/bin/true \
    /bin/sh monitoring/prometheus/render-config.sh

  if ! grep -F -- "$target" "$rendered_config" >/dev/null; then
    printf 'rendered Prometheus config does not contain target: %s\n' "$target" >&2
    exit 1
  fi

  if grep -F -- "__MONITORING_TARGET__" "$rendered_config" >/dev/null; then
    printf 'rendered Prometheus config still contains the target placeholder\n' >&2
    exit 1
  fi

  rm -f "$rendered_config"
  trap - EXIT HUP INT TERM
}

validate_monitor() {
  monitor_files="-f docker-compose.monitor.yml"

  # shellcheck disable=SC2086
  compose_config --quiet $monitor_files
  # shellcheck disable=SC2086
  assert_service_set "monitor" "grafana prometheus" $monitor_files
  # shellcheck disable=SC2086
  assert_no_profiles "monitor" $monitor_files

  # shellcheck disable=SC2086
  networks=$(compose_config --networks $monitor_files | normalize_lines)
  if [ "$networks" != "default" ]; then
    printf 'monitor must use only its default network: %s\n' "$networks" >&2
    exit 1
  fi

  assert_rendered_target "host.docker.internal:8080"
  assert_rendered_target "192.168.0.10:18080"
}

assert_compose_runner_command() {
  label=$1
  expected=$2
  shift 2

  : > "$compose_runner_output"
  PATH="$compose_runner_bin_dir:$PATH" \
    COMPOSE_RUNNER_OUTPUT="$compose_runner_output" \
    COMPOSE_RUNNER_MINIO_ID="${compose_runner_minio_id:-}" \
    scripts/compose.sh "$@"

  actual=$(cat "$compose_runner_output")
  if [ "$actual" != "$expected" ]; then
    printf '%s command mismatch\nexpected: %s\nactual:   %s\n' \
      "$label" "$expected" "$actual" >&2
    exit 1
  fi
}

assert_compose_runner_failure() {
  label=$1
  expected_message=$2
  shift 2

  if PATH="$compose_runner_bin_dir:$PATH" \
    COMPOSE_RUNNER_OUTPUT="$compose_runner_output" \
    scripts/compose.sh "$@" > /dev/null 2> "$compose_runner_error"; then
    printf '%s must fail\n' "$label" >&2
    exit 1
  fi

  if ! grep -F "$expected_message" "$compose_runner_error" > /dev/null; then
    printf '%s error mismatch: expected message containing %s\n' \
      "$label" "$expected_message" >&2
    exit 1
  fi
}

validate_compose_runner() {
  compose_runner_temp_dir=$(mktemp -d)
  compose_runner_bin_dir="$compose_runner_temp_dir/bin"
  compose_runner_output="$compose_runner_temp_dir/output"
  compose_runner_error="$compose_runner_temp_dir/error"
  mkdir "$compose_runner_bin_dir"
  trap 'rm -rf "$compose_runner_temp_dir"' EXIT HUP INT TERM

  cat > "$compose_runner_bin_dir/docker" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >> "$COMPOSE_RUNNER_OUTPUT"

case "$*" in
  *" ps -a -q minio")
    if [ -n "${COMPOSE_RUNNER_MINIO_ID:-}" ]; then
      printf '%s\n' "$COMPOSE_RUNNER_MINIO_ID"
    fi
    ;;
esac
EOF
  chmod +x "$compose_runner_bin_dir/docker"

  compose_runner_minio_id=
  assert_compose_runner_command \
    "dev first up" \
    "compose -f docker-compose.dev.yml -f docker-compose.infra.yml ps -a -q minio
compose -f docker-compose.dev.yml -f docker-compose.infra.yml up -d minio
compose -f docker-compose.dev.yml -f docker-compose.infra.yml --profile provision run --rm -T --interactive=false minio-provision
compose -f docker-compose.dev.yml -f docker-compose.infra.yml up -d --build" \
    dev up
  project_directory=$(git -C "$REPOSITORY_ROOT" worktree list --porcelain | sed -n '1s/^worktree //p')
  project_name=$(basename "$project_directory")
  mailpit_compose_prefix="compose --project-directory $project_directory --project-name $project_name -f $REPOSITORY_ROOT/docker-compose.dev.yml -f $REPOSITORY_ROOT/docker-compose.infra.yml -f $REPOSITORY_ROOT/docker-compose.mailpit.yml"
  compose_runner_minio_id=
  assert_compose_runner_command \
    "dev-mailpit first up" \
    "$mailpit_compose_prefix ps -a -q minio
$mailpit_compose_prefix up -d minio
$mailpit_compose_prefix --profile provision run --rm -T --interactive=false minio-provision
$mailpit_compose_prefix up -d --build" \
    dev-mailpit up
  compose_runner_minio_id=piku-minio
  assert_compose_runner_command \
    "dev-mailpit ps" \
    "$mailpit_compose_prefix ps" \
    dev-mailpit ps
  assert_compose_runner_command \
    "dev-mailpit down" \
    "$mailpit_compose_prefix down" \
    dev-mailpit down
  assert_compose_runner_command \
    "dev-mailpit logs" \
    "$mailpit_compose_prefix logs -f" \
    dev-mailpit logs
  assert_compose_runner_command \
    "dev-mailpit rebuild-app" \
    "$mailpit_compose_prefix up -d --build --no-deps app" \
    dev-mailpit rebuild-app
  compose_runner_minio_id=piku-minio
  assert_compose_runner_command \
    "prod subsequent up" \
    "compose -f docker-compose.prod.yml -f docker-compose.infra.yml ps -a -q minio
compose -f docker-compose.prod.yml -f docker-compose.infra.yml up -d --build" \
    prod up
  compose_runner_minio_id=
  assert_compose_runner_command \
    "prod down" \
    "compose -f docker-compose.prod.yml -f docker-compose.infra.yml down" \
    prod down
  assert_compose_runner_command \
    "prod rebuild-app" \
    "compose -f docker-compose.prod.yml -f docker-compose.infra.yml up -d --build --no-deps app" \
    prod rebuild-app
  assert_compose_runner_command \
    "monitor up" \
    "compose -f docker-compose.monitor.yml up -d" \
    monitor up
  assert_compose_runner_command \
    "dev ps" \
    "compose -f docker-compose.dev.yml -f docker-compose.infra.yml ps" \
    dev ps
  assert_compose_runner_command \
    "monitor logs" \
    "compose -f docker-compose.monitor.yml logs -f" \
    monitor logs
  assert_compose_runner_failure \
    "monitor rebuild-app" \
    "rebuild-app is only available for dev or prod" \
    monitor rebuild-app
  assert_compose_runner_failure \
    "unknown environment" \
    "usage:" \
    staging up
  assert_compose_runner_failure \
    "unknown action" \
    "usage:" \
    dev start
  assert_compose_runner_failure \
    "unknown dev-mailpit action" \
    "usage:" \
    dev-mailpit start

  rm -rf "$compose_runner_temp_dir"
  trap - EXIT HUP INT TERM
}

case "${1:-all}" in
  core)
    validate_core
    ;;
  monitor)
    validate_monitor
    ;;
  all)
    validate_core
    validate_monitor
    validate_compose_runner
    ;;
  *)
    printf 'usage: %s [core|monitor|all]\n' "$0" >&2
    exit 2
    ;;
esac

printf 'Docker Compose validation passed (%s)\n' "${1:-all}"
