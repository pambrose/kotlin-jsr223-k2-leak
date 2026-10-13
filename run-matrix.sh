#!/usr/bin/env bash
# Runs the harness for every Kotlin version and mode below, then prints one table row per run.
#
#   ./run-matrix.sh                                  # the default matrix
#   VERSIONS="2.4.10 2.4.21" MODES=new-engine ./run-matrix.sh
#   JAVA_VERSION=17 ./run-matrix.sh                  # run the harness on JDK 17 (any installed JDK)
#   EVALUATIONS=60 ./run-matrix.sh
#
# Each run's full output is kept in build/matrix/<version>-<mode>.txt.
set -euo pipefail
cd "$(dirname "$0")"

read -r -a versions <<< "${VERSIONS:-2.4.10 2.4.20-Beta1 2.4.20-Beta2 2.4.20 2.4.21 2.5.0-Beta1}"
read -r -a modes <<< "${MODES:-new-engine new-context new-engine-disposed same-context}"
evaluations="${EVALUATIONS:-40}"
java_arg=()
[[ -n "${JAVA_VERSION:-}" ]] && java_arg=("-PjavaVersion=${JAVA_VERSION}")

mkdir -p build/matrix
results=()
for version in "${versions[@]}"; do
  for mode in "${modes[@]}"; do
    out="build/matrix/${version}-${mode}.txt"
    echo "Kotlin ${version}, ${mode} ..." >&2
    if ./gradlew -q run "-PkotlinVersion=${version}" ${java_arg[@]+"${java_arg[@]}"} --args="${mode} ${evaluations}" > "${out}" 2>&1; then
      results+=("$(grep '^RESULT' "${out}" || echo "RESULT kotlin=${version} mode=${mode} (no result, see ${out})")")
    else
      results+=("RESULT kotlin=${version} mode=${mode} FAILED (see ${out})")
    fi
  done
done

echo
printf '%-14s %-21s %14s %13s %14s %16s\n' "Kotlin" "mode" "per eval" "retained" "environments" "Disposer entries"
for line in "${results[@]}"; do
  get() { sed -n "s/.* $1=\([^ ]*\).*/\1/p" <<< "${line}"; }
  version="$(sed -n 's/.*kotlin=\([^ ]*\).*/\1/p' <<< "${line}")"
  mode="$(sed -n 's/.*mode=\([^ ]*\).*/\1/p' <<< "${line}")"
  if [[ "${line}" == *retainedPerEvaluation=* ]]; then
    printf '%-14s %-21s %14s %13s %14s %16s\n' "${version}" "${mode}" \
      "$(get retainedPerEvaluation)" "$(get retainedHeap)" "$(get liveEnvironments)" "$(get disposerTreeEntries)"
  else
    printf '%-14s %-21s %s\n' "${version}" "${mode}" "${line#*mode=${mode} }"
  fi
done
