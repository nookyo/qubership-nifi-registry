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

. /opt/nifi-registry/scripts/loggingApi.sh

providers_file=${NIFI_REGISTRY_HOME}/conf/providers.xml

info "Setting flow provider configuration: Flow Storage Directory = ${NIFI_REGISTRY_FLOW_STORAGE_DIR:-./flow_storage}"
sed -i -E "s|^(.*)<property name=\"Flow Storage Directory\">(.*)</property>(.*)|\1<property name=\"Flow Storage Directory\">${NIFI_REGISTRY_FLOW_STORAGE_DIR:-./flow_storage}</property>\3|" "${providers_file}"

case ${NIFI_REGISTRY_FLOW_PROVIDER} in
    file)
        info "Using default provider = org.apache.nifi.registry.provider.flow.FileSystemFlowPersistenceProvider"
        ;;
    database)
        sed -i -E "s|^(.*)<class>org.apache.nifi.registry.provider.flow.FileSystemFlowPersistenceProvider</class>(.*)|\1<class>org.apache.nifi.registry.provider.flow.DatabaseFlowPersistenceProvider</class>\2|" "${providers_file}"
        ;;
    git)
        sed -i -E "s|^(.*)<class>org.apache.nifi.registry.provider.flow.FileSystemFlowPersistenceProvider</class>(.*)|\1<class>org.apache.nifi.registry.provider.flow.git.GitFlowPersistenceProvider</class><property name=\"Remote To Push\">${NIFI_REGISTRY_GIT_REMOTE:-}</property><property name=\"Remote Access User\">${NIFI_REGISTRY_GIT_USER:-}</property><property name=\"Remote Access Password\">${NIFI_REGISTRY_GIT_PASSWORD:-}</property>\2|" "${providers_file}"

        if [ ! -z "$NIFI_REGISTRY_GIT_REPO" ]; then
            sed -i -E "s|^(.*)<property name=\"Remote Access Password\">(.*)</property>(.*)|\1<property name=\"Remote Access Password\">\2</property><property name=\"Remote Clone Repository\">${NIFI_REGISTRY_GIT_REPO:-}</property>\3|" "${providers_file}"
        fi
        ;;
esac
