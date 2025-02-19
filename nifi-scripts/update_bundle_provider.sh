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

. /opt/nifi-registry/scripts/logging_api.sh

providers_file=${NIFI_REGISTRY_HOME}/conf/providers.xml

info "Setting extension bundle provider configuration: Extension Bundle Storage Directory = ${NIFI_REGISTRY_BUNDLE_STORAGE_DIR:-./extension_bundles}"
sed -i -E "s|^(.*)<property name=\"Extension Bundle Storage Directory\">(.*)</property>(.*)|\1<property name=\"Extension Bundle Storage Directory\">${NIFI_REGISTRY_BUNDLE_STORAGE_DIR:-./extension_bundles}</property>\3|" "${providers_file}"

case ${NIFI_REGISTRY_BUNDLE_PROVIDER} in
    file)
        info "Using default extension bundle provider = org.apache.nifi.registry.provider.extension.FileSystemBundlePersistenceProvider"
        ;;
    s3)
        info "ERROR: S3 extension bundle provider not supported"
        sleep 10
        exit 1
        ;;
esac
