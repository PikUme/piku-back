#!/bin/sh

set -eu

REPOSITORY_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$REPOSITORY_ROOT"

usage() {
  cat >&2 <<'EOF'
usage: scripts/compose.sh <dev|dev-mailpit|prod|monitor> <up|down|rebuild-app|ps|logs> [options]
EOF
}

if [ "$#" -lt 2 ]; then
  usage
  exit 2
fi

environment=$1
action=$2
shift 2

case "$environment" in
  dev | dev-mailpit | prod | monitor)
    ;;
  *)
    usage
    exit 2
    ;;
esac

run_compose() {
  case "$environment" in
    dev)
      docker compose \
        -f docker-compose.dev.yml \
        -f docker-compose.infra.yml \
        "$@"
      ;;
    dev-mailpit)
      project_directory=${DEV_COMPOSE_PROJECT_DIRECTORY:-$(git -C "$REPOSITORY_ROOT" worktree list --porcelain | sed -n '1s/^worktree //p')}
      if [ -z "$project_directory" ] || [ ! -d "$project_directory" ]; then
        printf 'Unable to locate the primary worktree for dev-mailpit\n' >&2
        exit 1
      fi
      project_name=${DEV_COMPOSE_PROJECT_NAME:-$(basename "$project_directory")}
      PIKU_BACK_APP_BUILD_CONTEXT="$REPOSITORY_ROOT" \
        docker compose \
          --project-directory "$project_directory" \
          --project-name "$project_name" \
          -f "$REPOSITORY_ROOT/docker-compose.dev.yml" \
          -f "$REPOSITORY_ROOT/docker-compose.infra.yml" \
          -f "$REPOSITORY_ROOT/docker-compose.mailpit.yml" \
          "$@"
      ;;
    prod)
      docker compose \
        -f docker-compose.prod.yml \
        -f docker-compose.infra.yml \
        "$@"
      ;;
    monitor)
      docker compose \
        -f docker-compose.monitor.yml \
        "$@"
      ;;
  esac
}

case "$action" in
  up)
    if [ "$environment" = "monitor" ]; then
      run_compose up -d "$@"
    else
      minio_container_id=$(run_compose ps -a -q minio)
      if [ -z "$minio_container_id" ]; then
        run_compose up -d minio
        run_compose \
          --profile provision \
          run --rm -T --interactive=false minio-provision
      fi
      run_compose up -d --build "$@"
    fi
    ;;
  down)
    run_compose down "$@"
    ;;
  rebuild-app)
    if [ "$environment" = "monitor" ]; then
      printf 'rebuild-app is only available for dev or prod\n' >&2
      exit 2
    fi
    run_compose up -d --build --no-deps "$@" app
    ;;
  ps)
    run_compose ps "$@"
    ;;
  logs)
    run_compose logs -f "$@"
    ;;
  *)
    usage
    exit 2
    ;;
esac
