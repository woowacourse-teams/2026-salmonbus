#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd -P)
[[ -z ${DOCKER_HOST:-} ]] || { echo 'Use a local Docker context.' >&2; exit 1; }
[[ $(docker context inspect --format '{{.Endpoints.docker.Host}}') == unix://* ]] \
  || { echo 'Use a local Unix-socket Docker context.' >&2; exit 1; }

temporary=$(mktemp -d)
project="salmonbus-image-check-$(date +%s)-$$"
workspace="$temporary/$project"
foreign="$project-foreign"
images=()
container="$project-held"
own_container=false

cleanup() {
  local result=$?
  trap - EXIT
  if $own_container; then
    docker container rm "$container" >/dev/null 2>&1 || true
  fi
  if ((${#images[@]})); then
    for image in "${images[@]}"; do
      docker image rm "$image" >/dev/null 2>&1 || true
    done
  fi
  rm -f "$workspace/dev/local.sh" "$temporary/empty.tar"
  rmdir "$workspace/dev" "$workspace" "$temporary"
  exit "$result"
}
trap cleanup EXIT

[[ -z $(docker image ls -q --filter "label=com.docker.compose.project=$project") ]]
[[ -z $(docker image ls -q --filter "label=com.docker.compose.project=$foreign") ]]
mkdir -p "$workspace/dev"
cp "$ROOT/dev/local.sh" "$workspace/dev/local.sh"
tar -cf "$temporary/empty.tar" -T /dev/null

make_image() {
  last_image=$(docker image import \
    --change "LABEL com.docker.compose.project=$1 local-check.revision=$3" \
    --change 'CMD ["/not-executed"]' "$temporary/empty.tar" "$2")
  images+=("$last_image")
}

make_image "$project" "$project:latest" old
old=$last_image
make_image "$project" "$project:latest" current
current=$last_image
make_image "$project" "$project:held" held
held=$last_image
# This container is never started; its image reference must still be protected.
docker container create --network none --name "$container" "$project:held" >/dev/null
own_container=true
make_image "$project" "$project:held" next-held
make_image "$foreign" "$foreign:latest" old-foreign
foreign_old=$last_image
make_image "$foreign" "$foreign:latest" current-foreign

if bash "$workspace/dev/local.sh" clean-images >/dev/null 2>&1; then
  echo 'Image cleanup must require --yes.' >&2; exit 1
fi
docker image inspect "$old" >/dev/null
bash "$workspace/dev/local.sh" clean-images --yes >/dev/null
if docker image inspect "$old" >/dev/null 2>&1; then
  echo 'An unused superseded project image should be removed.' >&2; exit 1
fi
[[ $(docker image inspect --format '{{.Id}}' "$project:latest") == "$current" ]]
docker image inspect "$held" "$foreign_old" >/dev/null
printf '%s\n' 'Unused project images removed; current tags, foreign images and stopped-container references preserved.'
