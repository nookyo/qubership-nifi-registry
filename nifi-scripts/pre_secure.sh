#!/bin/sh -e
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
# shellcheck disable=SC2016
. /opt/nifi-registry/scripts/logging_api.sh

scripts_dir='/opt/nifi-registry/scripts'

[ -f "${scripts_dir}/common.sh" ] && . "${scripts_dir}/common.sh"

# Setup Identity Mapping
sed -i -r -e "s|^\#\s*(nifi\.registry\.security\.identity\.mapping\.pattern\.dn=)|\1|" "${NIFI_REGISTRY_HOME}"/conf/nifi-registry.properties
sed -i -r -e "s|^\#\s*(nifi\.registry\.security\.identity\.mapping\.value\.dn=)|\1|" "${NIFI_REGISTRY_HOME}"/conf/nifi-registry.properties
prop_replace 'nifi.registry.security.identity.mapping.pattern.dn' '\^\.\*EMAILADDRESS=\(\[\^,\]\*\)\.\*\$'
prop_replace 'nifi.registry.security.identity.mapping.value.dn' "\$1"

{
    echo ""
    echo ""
    echo "nifi.registry.security.identity.mapping.pattern.dn2=^CN=(.*?), .*$"
    echo "nifi.registry.security.identity.mapping.value.dn2=$1"
} >>"${NIFI_REGISTRY_HOME}"/conf/nifi-registry.properties


#Change the location of the users.xml file and authorizations.xml
sed -i -e 's|<property name="Users File">\./conf/users.xml</property>|<property name="Users File">\./persistent_data/conf/users.xml</property>|' "${NIFI_REGISTRY_HOME}"/conf/authorizers.xml
sed -i -e 's|<property name="Authorizations File">\./conf/authorizations.xml</property>|<property name="Authorizations File">\./persistent_data/conf/authorizations.xml</property>|' "${NIFI_REGISTRY_HOME}"/conf/authorizers.xml

# Import CA certificates
if [ -d /tmp/cert ]; then
    if [ -z "${CERTIFICATE_FILE_PASSWORD}" ]; then
        export CERTIFICATE_FILE_PASSWORD="changeit"
    fi
    export CERTIFICATE_KEYSTORE_LOCATION="/etc/ssl/certs/java/cacerts"

    info "Importing certificates from /tmp/cert directory..."
    find /tmp/cert -print | grep -E '\.cer|\.pem' | grep -v '\.\.' | sed -E 's|/tmp/cert/(.*)|/tmp/cert/\1 \1|g' | xargs -n 2 --no-run-if-empty bash -c \
        'echo -file "$1" -alias "$2" ; keytool -importcert -cacerts -file "$1" -alias "$2"  -storepass ${CERTIFICATE_FILE_PASSWORD} -noprompt' argv0 || warn "Failed to import certificate"
else
    info "Directory /tmp/cert doesn't exist, skipping import."
fi

# Create conf directory
mkdir -p "${NIFI_REGISTRY_HOME}"/persistent_data/conf

export TRUSTSTORE_PATH="/tmp/tls-certs/truststore.p12"
export TRUSTSTORE_TYPE="PKCS12"
export KEYSTORE_PATH="/tmp/tls-certs/keystore.p12"
export KEYSTORE_TYPE="PKCS12"
TRUSTSTORE_PASSWORD=$(echo "$TRUSTSTORE_PASSWORD" | sed -e "s|&|\\\\&|g")
export TRUSTSTORE_PASSWORD
KEYSTORE_PASSWORD=$(echo "$KEYSTORE_PASSWORD" | sed -e "s|&|\\\\&|g")
export KEYSTORE_PASSWORD
KEY_PASSWORD=$(echo "$KEY_PASSWORD" | sed -e "s|&|\\\\&|g")
export KEY_PASSWORD

info "Done creating keystore."
