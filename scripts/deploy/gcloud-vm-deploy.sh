#!/usr/bin/env bash
set -Eeuo pipefail

app_name="meeting-automation-backend"
app_root="/opt/apps/${app_name}"
data_root="/var/lib/${app_name}"
image_tag="${1:?Usage: gcloud-vm-deploy.sh IMAGE_TAG}"
registry_host="us-central1-docker.pkg.dev"
image_name="${registry_host}/project-c43c730b-f69a-42f1-9ce/meeting-automation/${app_name}"
compose_file="${app_root}/compose.yml"
env_file="${app_root}/shared/.env"
runtime_env_source="/tmp/backend-runtime.env"
runtime_env_backup="${app_root}/shared/.env.previous"

if [[ ! -s "${runtime_env_source}" ]]; then
  echo "Missing uploaded runtime environment." >&2
  exit 1
fi
chmod 0600 "${runtime_env_source}"
trap 'rm -f "${runtime_env_source}"' EXIT
for required_setting in OPENAI_API_KEY DOCUMENT_SETTINGS_ENCRYPTION_KEY ALLOWED_ORIGINS; do
  if ! grep -Eq "^${required_setting}=.+$" "${runtime_env_source}"; then
    echo "${required_setting} is missing from the uploaded runtime environment." >&2
    exit 1
  fi
done

install -d -m 0750 "${app_root}/shared" "${data_root}/settings" "${data_root}/audio"
install -m 0644 "/tmp/compose.yml" "${compose_file}"
had_previous_env=false
if [[ -f "${env_file}" ]]; then
  install -m 0600 "${env_file}" "${runtime_env_backup}"
  had_previous_env=true
fi
install -m 0600 "${runtime_env_source}" "${env_file}"

restore_previous_env() {
  if [[ "${had_previous_env}" == true ]]; then
    mv -f "${runtime_env_backup}" "${env_file}"
  else
    rm -f "${runtime_env_backup}"
  fi
}

previous_image=""
if docker container inspect "${app_name}" >/dev/null 2>&1; then
  previous_image="$(docker inspect --format '{{.Config.Image}}' "${app_name}")"
fi

deploy_image() {
  local tag="$1"
  local access_token
  access_token="$(curl --fail --silent --show-error \
    -H 'Metadata-Flavor: Google' \
    'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"

  if ! printf '%s' "${access_token}" | docker login --username oauth2accesstoken --password-stdin "https://${registry_host}" >/dev/null; then
    unset access_token
    return 1
  fi
  unset access_token

  if ! IMAGE_TAG="${tag}" docker compose --project-name "${app_name}" --file "${compose_file}" --env-file "${env_file}" pull; then
    docker logout "https://${registry_host}" >/dev/null 2>&1 || true
    return 1
  fi
  docker logout "https://${registry_host}" >/dev/null 2>&1 || true
  IMAGE_TAG="${tag}" docker compose --project-name "${app_name}" --file "${compose_file}" --env-file "${env_file}" up --detach --remove-orphans
}

if ! deploy_image "${image_tag}"; then
  echo "Could not pull or start the new image ${image_tag}." >&2
  restore_previous_env
  if [[ -n "${previous_image}" ]]; then
    previous_tag="${previous_image##*:}"
    deploy_image "${previous_tag}"
    echo "Restored previous image ${previous_image}." >&2
  fi
  exit 1
fi

for attempt in $(seq 1 30); do
  if curl --fail --silent http://127.0.0.1:9010/actuator/health >/dev/null; then
    if [[ -n "${previous_image}" && "${previous_image}" != "${image_name}:${image_tag}" ]]; then
      docker image rm "${previous_image}" >/dev/null 2>&1 || true
    fi
    rm -f "${runtime_env_backup}"
    echo "${app_name} is healthy on port 9010 (${image_tag})."
    exit 0
  fi
  sleep 2
done

echo "New container did not become healthy; checking its logs." >&2
docker logs --tail 80 "${app_name}" >&2 || true

if [[ -n "${previous_image}" ]]; then
  previous_tag="${previous_image##*:}"
  restore_previous_env
  deploy_image "${previous_tag}"
  echo "Restored previous image ${previous_image}." >&2
else
  restore_previous_env
  docker compose --project-name "${app_name}" --file "${compose_file}" --env-file "${env_file}" down || true
fi
exit 1
