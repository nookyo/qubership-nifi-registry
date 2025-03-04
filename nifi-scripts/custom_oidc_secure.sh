#!/bin/bash -e
# Copyright 2020-2025 NetCracker Technology Corporation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# shellcheck source=/dev/null
. /opt/nifi-registry/scripts/logging_api.sh

scripts_dir='/opt/nifi-registry/scripts'

[ -f "${scripts_dir}/common.sh" ] && . "${scripts_dir}/common.sh"
[ -f "${scripts_dir}/get-oidc-params.sh" ] && . "${scripts_dir}/get-oidc-params.sh"

if [ -z "$OIDC_DISCOVERY_URL_NEW" ]; then
    info "Using OIDC_DISCOVERY_URL = $OIDC_DISCOVERY_URL for OIDC configuration"
    OIDC_DISCOVERY_URL_NEW="$OIDC_DISCOVERY_URL"
else
    info "Ignoring old OIDC_DISCOVERY_URL = $OIDC_DISCOVERY_URL, if set"
    info "Using OIDC_DISCOVERY_URL = $OIDC_DISCOVERY_URL_NEW"
fi

# Setup OpenId Connect SSO Properties
prop_replace 'nifi.registry.security.user.oidc.discovery.url'  "${OIDC_DISCOVERY_URL_NEW}"
prop_replace 'nifi.registry.security.user.oidc.client.id'      "${OIDC_CLIENT_ID}"
prop_replace 'nifi.registry.security.user.oidc.client.secret'  "${OIDC_CLIENT_SECRET}"