#!/bin/bash -e
. /opt/nifi-registry/scripts/logging_api.sh

#    Licensed to the Apache Software Foundation (ASF) under one or more
#    contributor license agreements.  See the NOTICE file distributed with
#    this work for additional information regarding copyright ownership.
#    The ASF licenses this file to You under the Apache License, Version 2.0
#    (the "License"); you may not use this file except in compliance with
#    the License.  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.

scripts_dir='/opt/nifi-registry/scripts'

#run before start operations:
[ -f "$scripts_dir/before_start.sh" ] && . "$scripts_dir/before_start.sh"
[ -f "$scripts_dir/start_ext.sh" ] && . "$scripts_dir/start_ext.sh"

[ -f "${scripts_dir}/common.sh" ] && . "${scripts_dir}/common.sh"

cp ${NIFI_REGISTRY_HOME}/conf-template/* ${NIFI_REGISTRY_HOME}/conf/
cp ${NIFI_REGISTRY_HOME}/conf-template-custom/* ${NIFI_REGISTRY_HOME}/conf/

# Establish baseline properties
prop_replace 'nifi.registry.web.http.port'      "${NIFI_REGISTRY_WEB_HTTP_PORT:-18080}"
prop_replace 'nifi.registry.web.http.host'      "${NIFI_REGISTRY_WEB_HTTP_HOST:-$HOSTNAME}"


if [ ! -z "${NIFI_REG_JVM_HEAP_INIT}" -a "${NIFI_REG_JVM_HEAP_INIT}" != '${ENV_NIFI_REG_JVM_HEAP_INIT}' ]; then
    prop_replace 'java.arg.2'       "-Xms${NIFI_REG_JVM_HEAP_INIT}" ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
fi

if [ ! -z "${NIFI_REG_JVM_HEAP_MAX}" -a "${NIFI_REG_JVM_HEAP_MAX}" != '${ENV_NIFI_REG_JVM_HEAP_MAX}' ]; then
    prop_replace 'java.arg.3'       "-Xmx${NIFI_REG_JVM_HEAP_MAX}" ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
fi

if [ ! -z "${NIFI_REG_XSS}" -a "${NIFI_REG_XSS}" != '${ENV_NIFI_REG_XSS}' ]; then
    echo "" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
    echo "" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
    echo "#Thread stack size" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
    echo "java.arg.7=-Xss${NIFI_REG_XSS}" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
    echo "" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
fi

#Temp enable native memory tracking:
echo "" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
echo "" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
if [ ! -z "${NIFI_DEBUG_NATIVE_MEMORY}" ]; then
  echo "#Native memory tracking" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
  echo "java.arg.8=-XX:NativeMemoryTracking=detail" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
fi
echo "" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf

if [ ! -z "${NIFI_REG_ADDITIONAL_JVM_ARGS}" -a "${NIFI_REG_ADDITIONAL_JVM_ARGS}" != '${ENV_NIFI_REG_ADDITIONAL_JVM_ARGS}' ]; then
    i=9
    read -a addJvmArgArr <<< "$NIFI_REG_ADDITIONAL_JVM_ARGS"
    for addJvmArg in "${addJvmArgArr[@]}"; do
        info "Add $addJvmArg in bootstrap.conf"
        echo "java.arg.$i=$addJvmArg" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
        echo "" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
        i=$(($i+1))
    done
fi

before_args_processing

if [ ! -z "${X_JAVA_ARGS}" ]; then
    if [ -z "$i" ]; then
        i=9
    fi
    read -a addJvmArgArr2 <<< "$X_JAVA_ARGS"
    for addJvmArg in "${addJvmArgArr2[@]}"; do
        info "Add $addJvmArg in bootstrap.conf"
        echo "java.arg.$i=$addJvmArg" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
        echo "" >> ${NIFI_REGISTRY_HOME}/conf/bootstrap.conf
        i=$(($i+1))
    done
fi

. ${scripts_dir}/update_database.sh

# Check if we are secured or unsecured
case ${AUTH} in
    tls)
        info 'Enabling Two-Way SSL user authentication'
        . "${scripts_dir}/pre_secure.sh"
        . "${scripts_dir}/secure.sh"
        ;;
    ldap)
        info 'Enabling LDAP user authentication'
        # Reference ldap-provider in properties
        prop_replace 'nifi.registry.security.identity.provider' 'ldap-identity-provider'
        prop_replace 'nifi.registry.security.needClientAuth' 'false'

        . "${scripts_dir}/secure.sh"
        . "${scripts_dir}/update_login_providers.sh"
        ;;
    oidc)
        info 'Enabling OIDC user authentication'
        prop_replace 'nifi.registry.security.needClientAuth' 'false'

        . "${scripts_dir}/pre_secure.sh"
        . "${scripts_dir}/secure.sh"
        . "${scripts_dir}/update_oidc_properties.sh"
        . "${scripts_dir}/custom_oidc_secure.sh"
        ;;
esac

. "${scripts_dir}/update_flow_provider.sh"

info "Create or Retrieve DataSource Initiated..."
info "Checking whether to use PostgreSQL or not. Value is $NIFI_REG_USE_PGDB"
if [ "$NIFI_REG_USE_PGDB" = 'true' ]; then
    . "${scripts_dir}/GetDBConnectionDetails.sh"
    ${JAVA_HOME}/bin/java -jar ${NIFI_REGISTRY_HOME}/db_schema_gen/nifi-registry-util.jar "$dbUrl" "$dbUsername" "$dbPassword" "${NIFI_REG_MIGRATE_TO_DB}"
    . "${scripts_dir}/connect_to_db.sh"
    info "Configuring providers and authorizers to use Database"
    cp ${scripts_dir}/DbFlowProviders.xml ${NIFI_REGISTRY_HOME}/conf/providers.xml
    cp ${scripts_dir}/DbFlowAuthorizers.xml ${NIFI_REGISTRY_HOME}/conf/authorizers.xml
    sed -i -E "s|^(.*)<property name=\"Initial User Identity 1\"></property>(.*)|\1<property name=\"Initial User Identity 1\">${INITIAL_ADMIN_IDENTITY}</property>\2|" ${NIFI_REGISTRY_HOME}/conf/authorizers.xml
    sed -i -E "s|^(.*)<property name=\"Initial Admin Identity\"></property>(.*)|\1<property name=\"Initial Admin Identity\">${INITIAL_ADMIN_IDENTITY}</property>\2|" ${NIFI_REGISTRY_HOME}/conf/authorizers.xml
    
    if [ "$NIFI_REG_MIGRATE_TO_DB" = 'true' ]; then
        [ -f "${NIFI_REGISTRY_HOME}/persistent_data/database/nifi-registry-primary.mv.db" ] && cp ${NIFI_REGISTRY_HOME}/persistent_data/database/nifi-registry-primary.mv.db ${NIFI_REGISTRY_HOME}/persistent_data/database/nifi-registry.mv.db
    fi
fi

# Do after replacement of DbFlowProviders.xml:
. "${scripts_dir}/update_bundle_provider.sh"

# Continuously provide logs so that 'docker logs' can produce them
tail -F "${NIFI_REGISTRY_HOME}/logs/nifi-registry-app.log" &
tail_pid="$!"
"${NIFI_REGISTRY_HOME}/bin/nifi-registry.sh" run &
nifi_registry_pid="$!"

trap 'echo Received trapped signal, beginning shutdown...;${NIFI_REGISTRY_HOME}/bin/nifi-registry.sh stop;kill -9 "$tail_pid";exit 0;' TERM HUP INT;
trap ":" EXIT

info NiFi-Registry running with PID ${nifi_registry_pid}.
wait ${nifi_registry_pid}

javaRetCode=$?
info "Java process ended with return code ${javaRetCode}"
after_java_end