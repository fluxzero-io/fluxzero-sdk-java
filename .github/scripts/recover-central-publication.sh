#!/usr/bin/env bash

set -euo pipefail

readonly CENTRAL_STATUS_URL="${CENTRAL_BASE_URL:-https://central.sonatype.com}/api/v1/publisher/status"
readonly CENTRAL_WAIT_TIMEOUT_SECONDS="${CENTRAL_WAIT_TIMEOUT_SECONDS:-3600}"
readonly CENTRAL_POLL_INTERVAL_SECONDS="${CENTRAL_POLL_INTERVAL_SECONDS:-5}"
readonly CENTRAL_STATUS_RETRIES="${CENTRAL_STATUS_RETRIES:-3}"
readonly CENTRAL_STATUS_RETRY_DELAY_SECONDS="${CENTRAL_STATUS_RETRY_DELAY_SECONDS:-5}"

is_retryable_status_failure() {
  local deployment_log="$1"

  grep -Eq \
    'Cannot get deployment status\. Response status code: (500|502|503|504)([^0-9]|$)' \
    "$deployment_log"
}

extract_deployment_id() {
  local deployment_log="$1"

  sed -nE \
    's/.*Uploaded bundle successfully.*deploymentId: ([[:xdigit:]-]+).*/\1/p' \
    "$deployment_log" | tail -n 1
}

request_deployment_status() {
  local deployment_id="$1"
  local authorization="$2"
  local response_file="$3"

  curl --silent --show-error --fail-with-body \
    --retry "$CENTRAL_STATUS_RETRIES" \
    --retry-delay "$CENTRAL_STATUS_RETRY_DELAY_SECONDS" \
    --retry-max-time 60 \
    --connect-timeout 10 \
    --max-time 20 \
    --request POST \
    --header "Authorization: Bearer $authorization" \
    --header "Accept: application/json" \
    --output "$response_file" \
    "$CENTRAL_STATUS_URL?id=$deployment_id"
}

wait_before_next_poll() {
  sleep "$CENTRAL_POLL_INTERVAL_SECONDS"
}

wait_for_central_publication() {
  local deployment_id="$1"
  local authorization="$2"
  local response_file
  local state
  local deadline=$((SECONDS + CENTRAL_WAIT_TIMEOUT_SECONDS))

  response_file="$(mktemp)"

  echo "Retrying status checks for existing Maven Central deployment $deployment_id"

  while ((SECONDS < deadline)); do
    if ! request_deployment_status "$deployment_id" "$authorization" "$response_file"; then
      rm -f "$response_file"
      return 1
    fi

    if ! state="$(jq -er '.deploymentState | strings' "$response_file")"; then
      echo "Maven Central returned an invalid deployment status response" >&2
      jq . "$response_file" >&2 || true
      rm -f "$response_file"
      return 1
    fi

    echo "Maven Central deployment $deployment_id is $state"

    case "$state" in
      PUBLISHED)
        jq -r '.warnings[]? | "Maven Central warning: \(.)"' "$response_file"
        rm -f "$response_file"
        return 0
        ;;
      FAILED)
        echo "Maven Central deployment $deployment_id failed" >&2
        jq '.errors // {}' "$response_file" >&2
        rm -f "$response_file"
        return 1
        ;;
      PENDING | VALIDATING | VALIDATED | PUBLISHING)
        wait_before_next_poll
        ;;
      *)
        echo "Maven Central returned unknown deployment state: $state" >&2
        rm -f "$response_file"
        return 1
        ;;
    esac
  done

  echo "Timed out waiting for Maven Central deployment $deployment_id" >&2
  rm -f "$response_file"
  return 1
}

main() {
  if [[ "$#" -ne 1 ]]; then
    echo "Usage: $0 <maven-deployment-log>" >&2
    return 2
  fi

  local deployment_log="$1"
  local deployment_id
  local authorization

  if ! is_retryable_status_failure "$deployment_log"; then
    echo "Maven deployment did not fail on a retryable Central status response" >&2
    return 1
  fi

  deployment_id="$(extract_deployment_id "$deployment_log")"
  if [[ ! "$deployment_id" =~ ^[[:xdigit:]]{8}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{4}-[[:xdigit:]]{12}$ ]]; then
    echo "Could not recover a valid Maven Central deployment id from the Maven log" >&2
    return 1
  fi

  : "${MAVEN_USERNAME:?MAVEN_USERNAME is required}"
  : "${MAVEN_PASSWORD:?MAVEN_PASSWORD is required}"

  authorization="$(printf '%s:%s' "$MAVEN_USERNAME" "$MAVEN_PASSWORD" | base64 | tr -d '\r\n')"
  wait_for_central_publication "$deployment_id" "$authorization"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
