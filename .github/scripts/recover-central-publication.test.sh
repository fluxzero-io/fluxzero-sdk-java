#!/usr/bin/env bash

set -euo pipefail

CENTRAL_WAIT_TIMEOUT_SECONDS=2
CENTRAL_POLL_INTERVAL_SECONDS=0
source "$(dirname "$0")/recover-central-publication.sh"

retryable_log="$(mktemp)"
non_retryable_log="$(mktemp)"
trap 'rm -f "$retryable_log" "$non_retryable_log"' EXIT

readonly TEST_DEPLOYMENT_ID="e974e1ed-856f-4424-8712-9a4db24e9fa6"

printf '%s\n' \
  "Uploaded bundle successfully, deploymentId: $TEST_DEPLOYMENT_ID" \
  "Cannot get deployment status. Response status code: 502 response message: Bad Gateway" \
  > "$retryable_log"
printf '%s\n' "Cannot get deployment status. Response status code: 401" > "$non_retryable_log"

is_retryable_status_failure "$retryable_log"
[[ "$(extract_deployment_id "$retryable_log")" == "$TEST_DEPLOYMENT_ID" ]]

if is_retryable_status_failure "$non_retryable_log"; then
  echo "Authentication failures must not be retried" >&2
  exit 1
fi

request_count=0
request_deployment_status() {
  local response_file="$3"
  request_count=$((request_count + 1))

  if [[ "$request_count" -eq 1 ]]; then
    printf '%s\n' '{"deploymentState":"PUBLISHING"}' > "$response_file"
  else
    printf '%s\n' '{"deploymentState":"PUBLISHED","warnings":[]}' > "$response_file"
  fi
}

wait_before_next_poll() {
  :
}

wait_for_central_publication "$TEST_DEPLOYMENT_ID" "test-authorization"
[[ "$request_count" -eq 2 ]]

request_deployment_status() {
  local response_file="$3"
  printf '%s\n' '{"deploymentState":"FAILED","errors":{"publishing":["failed"]}}' > "$response_file"
}

if wait_for_central_publication "$TEST_DEPLOYMENT_ID" "test-authorization"; then
  echo "A failed Central deployment must fail the recovery" >&2
  exit 1
fi

request_deployment_status() {
  local response_file="$3"
  printf '%s\n' '{"deploymentState":"PUBLISHED","warnings":[]}' > "$response_file"
}

export MAVEN_USERNAME="test-user"
export MAVEN_PASSWORD="test-password"
main "$retryable_log"

echo "Maven Central publication recovery tests passed"
