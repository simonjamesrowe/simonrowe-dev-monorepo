#!/usr/bin/env bash
#
# Tests for scripts/ci/retag-image.sh, which publishes an unchanged image under a new commit's
# tag by copying the previous commit's manifest within the registry.
#
# What matters is that a re-tag is an EXACT copy (same digest, so the deploy plan on each host
# sees "unchanged" and leaves the container running), that a multi-arch image is only ever
# re-tagged from a previous image carrying every platform (or the amd64 NUC could not pull the
# new tag), and that every failure answers `retagged=false` so Publish builds instead.
#
# `docker` is a stub on PATH backed by a directory standing in for the registry: each tag is a
# `.raw` manifest file plus a `.digest` file. The real buildx behaviour the script relies on
# (a single source that is already an index is copied byte-for-byte whatever --prefer-index
# says; a lone manifest only with --prefer-index=false) was checked against registry:2 by hand
# and is recorded in the script; the stub models it so that dropping the flag fails here.
#
# Auto-discovered by run-tests.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
RETAG="$PROJECT_DIR/scripts/ci/retag-image.sh"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/registry"

cat >"$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
# Stub of `docker buildx imagetools` over a directory registry. Every call is logged.
set -euo pipefail
echo "$*" >>"$STUB_LOG"
key() { printf '%s' "$1" | sed 's#[/:@]#_#g'; }
[[ "${1:-} ${2:-}" == "buildx imagetools" ]] || { echo "stub: unexpected docker $*" >&2; exit 99; }
shift 2
case "${1:-}" in
  inspect)
    shift
    mode=pretty
    while [[ $# -gt 1 ]]; do
      case "$1" in
        --raw) mode=raw; shift ;;
        --format) mode=digest; shift 2 ;;
        *) echo "stub: unexpected inspect flag $1" >&2; exit 99 ;;
      esac
    done
    ref="$1"
    f="$STUB_REGISTRY/$(key "$ref")"
    [[ -f "$f.raw" ]] || { echo "ERROR: $ref: not found" >&2; exit 1; }
    case "$mode" in
      raw) cat "$f.raw" ;;
      digest) cat "$f.digest" ;;
      *) echo "Name: $ref" ;;
    esac
    ;;
  create)
    shift
    prefer_index=true tag="" src=""
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --prefer-index=false) prefer_index=false; shift ;;
        --tag|-t) tag="$2"; shift 2 ;;
        -*) echo "stub: unexpected create flag $1" >&2; exit 99 ;;
        *) src="$1"; shift ;;
      esac
    done
    [[ "${STUB_CREATE:-}" == fail ]] && { echo "ERROR: push denied" >&2; exit 1; }
    s="$STUB_REGISTRY/$(key "$src")" d="$STUB_REGISTRY/$(key "$tag")"
    [[ -f "$s.raw" ]] || { echo "ERROR: $src: not found" >&2; exit 1; }
    cp "$s.raw" "$d.raw"
    cp "$s.digest" "$d.digest"
    is_index="$(jq -r 'if (.manifests | type) == "array" then 1 else 0 end' "$s.raw")"
    # buildx: a lone manifest is wrapped in a new index unless --prefer-index=false; an
    # index is returned as-is. STUB_CREATE=wrap forces a changed digest regardless.
    if [[ "${STUB_CREATE:-}" == wrap || ( "$is_index" == 0 && "$prefer_index" == true ) ]]; then
      echo "sha256:wrapped" >"$d.digest"
    fi
    echo "pushed $tag" >&2
    ;;
  *) echo "stub: unexpected imagetools $*" >&2; exit 99 ;;
esac
STUB
chmod +x "$work/bin/docker"

export PATH="$work/bin:$PATH"
export STUB_REGISTRY="$work/registry"
export STUB_LOG="$work/docker.log"

IMAGE="ghcr.io/example/app"

put() { # put <tag> <digest> <raw json>
  local k
  k="$(printf '%s' "$IMAGE:$1" | sed 's#[/:@]#_#g')"
  printf '%s' "$3" >"$STUB_REGISTRY/$k.raw"
  printf '%s\n' "$2" >"$STUB_REGISTRY/$k.digest"
}
digest_of() {
  local k
  k="$(printf '%s' "$IMAGE:$1" | sed 's#[/:@]#_#g')"
  cat "$STUB_REGISTRY/$k.digest" 2>/dev/null || echo "absent"
}

SINGLE='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"digest":"sha256:c1"},"layers":[{"digest":"sha256:l1"}]}'
# What a native build per runner merged by `imagetools create` looks like, including the
# unknown/unknown attestation entries buildx adds when provenance is on.
MULTI='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
  {"digest":"sha256:arm","platform":{"os":"linux","architecture":"arm64"}},
  {"digest":"sha256:amd","platform":{"os":"linux","architecture":"amd64"}},
  {"digest":"sha256:att","platform":{"os":"unknown","architecture":"unknown"}}]}'
MULTI_VARIANT='{"schemaVersion":2,"manifests":[
  {"digest":"sha256:arm","platform":{"os":"linux","architecture":"arm64","variant":"v8"}},
  {"digest":"sha256:amd","platform":{"os":"linux","architecture":"amd64"}}]}'
# The last arm64-only build pushed with provenance: an index, but without amd64.
ARM_ONLY_INDEX='{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
  {"digest":"sha256:arm","platform":{"os":"linux","architecture":"arm64"}},
  {"digest":"sha256:att","platform":{"os":"unknown","architecture":"unknown"}}]}'

put single sha256:single "$SINGLE"
put multi sha256:multi "$MULTI"
put variant sha256:variant "$MULTI_VARIANT"
put armonly sha256:armonly "$ARM_ONLY_INDEX"

BOTH="linux/arm64,linux/amd64"

checks=0
failures=0

# run <previous> <new> [platforms] - sets $out (stdout) and $log (docker calls)
run() {
  : >"$STUB_LOG"
  local status
  set +e
  out="$("$RETAG" "$IMAGE" "$@" 2>"$work/stderr")"
  status=$?
  set -e
  log="$(cat "$STUB_LOG")"
  if [[ "$status" -ne 0 ]]; then
    out="exit $status: $out $(cat "$work/stderr")"
  fi
}

check() {
  local description="$1"
  shift
  checks=$((checks + 1))
  if "$@"; then
    echo "    ok: $description"
  else
    failures=$((failures + 1))
    echo "    FAIL: $description"
    echo "          stdout: $out"
    echo "          stderr: $(cat "$work/stderr" 2>/dev/null)"
    echo "          docker: $(tr '\n' ';' <<<"$log")"
  fi
}

is() { [[ "$1" == "$2" ]]; }
created() { grep -q '^buildx imagetools create' <<<"$log"; }
not_created() { ! created; }

echo "  a single-platform manifest (backend, frontend)"
run single n1
check "is re-tagged" is "$out" "retagged=true"
check "keeps its digest exactly" is "$(digest_of n1)" "sha256:single"
check "is copied with --prefer-index=false, which keeps a lone manifest from being wrapped" \
  grep -q -- '--prefer-index=false --tag ghcr.io/example/app:n1 ghcr.io/example/app:single' <<<"$log"

echo "  a multi-platform index (software-factory, page-reader)"
run multi n2 "$BOTH"
check "is re-tagged when it carries every required platform" is "$out" "retagged=true"
check "keeps its index digest exactly" is "$(digest_of n2)" "sha256:multi"
k="$(printf '%s' "$IMAGE:n2" | sed 's#[/:@]#_#g')"
check "keeps every platform's manifest (the whole index is copied)" \
  is "$(jq -c '[.manifests[].digest]' "$STUB_REGISTRY/$k.raw")" '["sha256:arm","sha256:amd","sha256:att"]'
run multi n3
check "is re-tagged when no platforms are required" is "$out" "retagged=true"
run variant n4 "$BOTH"
check "accepts linux/arm64/v8 for a required linux/arm64" is "$out" "retagged=true"

echo "  a previous image that cannot serve every required platform is rebuilt"
run single n5 "$BOTH"
check "a single manifest answers false when platforms are required" is "$out" "retagged=false"
check "and nothing is pushed" not_created
check "the new tag does not exist" is "$(digest_of n5)" "absent"
run armonly n6 "$BOTH"
check "an index without amd64 (the last arm64-only build) answers false" is "$out" "retagged=false"
check "and nothing is pushed" not_created
grep -q 'linux/amd64' "$work/stderr" && amd_named=true || amd_named=false
check "the narration names the missing platform" is "$amd_named" true

echo "  a missing previous image is rebuilt"
run nosuch n7 "$BOTH"
check "a previous tag that does not exist answers false" is "$out" "retagged=false"
check "and nothing is pushed" not_created
run nosuch n8
check "also without platforms" is "$out" "retagged=false"
run "" n9
check "no previous commit answers false" is "$out" "retagged=false"
check "without asking the registry" is "$log" ""
run 0000000000000000000000000000000000000000 n10 "$BOTH"
check "an all-zero previous commit (first push) answers false" is "$out" "retagged=false"
check "without asking the registry" is "$log" ""

echo "  a re-tag that does not come out exact is rebuilt"
STUB_CREATE=fail run multi n11 "$BOTH"
check "a failed imagetools create answers false" is "$out" "retagged=false"
STUB_CREATE=wrap run single n12
check "a copy whose digest differs from the source answers false" is "$out" "retagged=false"

echo
echo "  $checks checks, $failures failures"
[[ "$failures" -eq 0 ]]
