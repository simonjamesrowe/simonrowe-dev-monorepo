#!/bin/sh
set -eu

namespace=${TEMPORAL_NAMESPACE:-default}
temporal_address=${TEMPORAL_ADDRESS:-temporal:7233}
retention=${TEMPORAL_NAMESPACE_RETENTION:-30d}
maximum_attempts=${TEMPORAL_HEALTH_CHECK_MAX_ATTEMPTS:-30}
sleep_seconds=${TEMPORAL_HEALTH_CHECK_SLEEP_SECONDS:-5}

attempt=1
until temporal operator cluster health --address "$temporal_address"; do
  if [ "$attempt" -ge "$maximum_attempts" ]; then
    echo "Temporal did not become healthy after $maximum_attempts attempts"
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep "$sleep_seconds"
done

if temporal operator namespace describe \
    --namespace "$namespace" \
    --address "$temporal_address" >/dev/null 2>&1; then
  echo "Temporal namespace '$namespace' already exists"
else
  temporal operator namespace create \
    --namespace "$namespace" \
    --retention "$retention" \
    --address "$temporal_address"
  echo "Temporal namespace '$namespace' created"
fi
