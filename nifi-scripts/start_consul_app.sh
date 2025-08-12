#!/bin/bash -e

# shellcheck source=/dev/null
# shellcheck disable=SC2034
# shellcheck disable=SC2086
. /opt/nifi-registry/scripts/logging_api.sh

info "Starting consul app with options: $CONSUL_CONFIG_JAVA_OPTIONS"
eval "$JAVA_HOME"/bin/java "$CONSUL_CONFIG_JAVA_OPTIONS" \
    -jar "$NIFI_REGISTRY_HOME"/utility-lib/qubership-nifi-registry-consul-application.jar org.qubership.cloud.nifi.registry.config.NifiRegistryPropertiesLookup &
consul_pid=$!

info "Consul application pid: $consul_pid"
