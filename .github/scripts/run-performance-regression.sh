#!/usr/bin/env bash
set -euo pipefail

root_dir="$(git rev-parse --show-toplevel)"
base_sha="${PERFORMANCE_BASE_SHA:-}"
if [[ -z "${base_sha}" ]]; then
    echo "PERFORMANCE_BASE_SHA must contain the pull request base commit" >&2
    exit 2
fi

work_dir="$(mktemp -d)"
base_worktree="${work_dir}/base-worktree"
cleanup() {
    if [[ -d "${base_worktree}" ]]; then
        git -C "${root_dir}" worktree remove --force "${base_worktree}" >/dev/null 2>&1 || true
    fi
    rm -rf "${work_dir}"
}
trap cleanup EXIT

echo "Preparing benchmark jars for base ${base_sha} and head $(git -C "${root_dir}" rev-parse HEAD)"
git -C "${root_dir}" worktree add --detach "${base_worktree}" "${base_sha}"

(
    cd "${base_worktree}"
    ./mvnw -q -pl sdk -am -DskipTests install
)
(
    cd "${root_dir}"
    ./mvnw -q -f benchmarks/pom.xml -DskipTests clean package
)
cp "${root_dir}/benchmarks/target/fluxzero-benchmarks.jar" "${work_dir}/base.jar"

(
    cd "${root_dir}"
    ./mvnw -q -pl sdk -am -DskipTests install
    ./mvnw -q -f benchmarks/pom.xml -DskipTests clean package
)
cp "${root_dir}/benchmarks/target/fluxzero-benchmarks.jar" "${work_dir}/head.jar"

run_jmh() {
    local jar="$1"
    local output="$2"
    local log_file="${output%.json}.log"
    if ! java -jar "${jar}" '.*HotPathBenchmark.*' \
            -t 1 -f 1 -wi 2 -i 3 -w 400ms -r 600ms \
            -prof gc -rf json -rff "${output}" >"${log_file}" 2>&1; then
        cat "${log_file}" >&2
        return 1
    fi
}

echo "Running scenarios in ABBA order"
run_jmh "${work_dir}/base.jar" "${work_dir}/base-1.json"
run_jmh "${work_dir}/head.jar" "${work_dir}/head-1.json"
run_jmh "${work_dir}/head.jar" "${work_dir}/head-2.json"
run_jmh "${work_dir}/base.jar" "${work_dir}/base-2.json"

python3 "${root_dir}/.github/scripts/compare-benchmarks.py" \
    --base "${work_dir}/base-1.json" --base "${work_dir}/base-2.json" \
    --head "${work_dir}/head-1.json" --head "${work_dir}/head-2.json" \
    --time-threshold "${PERFORMANCE_TIME_THRESHOLD:-0.15}" \
    --allocation-threshold "${PERFORMANCE_ALLOCATION_THRESHOLD:-0.15}" \
    --allocation-min-bytes "${PERFORMANCE_ALLOCATION_MIN_BYTES:-256}"
