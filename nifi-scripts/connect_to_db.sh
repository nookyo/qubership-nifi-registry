#!/bin/bash
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

delete_temp_files(){
    rm -f databaseInfo.json
}

dbDriver="org.postgresql.Driver"
dbDriverDir="$NIFI_REGISTRY_HOME/lib/"
dbDirectory="./persistent_data/database"
dbUrlAppends=";LOCK_TIMEOUT=25000;WRITE_DELAY=0;AUTO_SERVER=FALSE"

if [ -n "$dbUrl" ]; then
    if [[ $dbUrl == *[?]* ]]; then
        databaseUrl="$dbUrl&currentSchema=nifi_registry"
    else
        databaseUrl="$dbUrl?currentSchema=nifi_registry"
    fi

    escDatabaseUrl="${databaseUrl//&/\\&}"
fi

if [ -z "$database" ]; then info "Database name not available"; else info "Configuring Database :- $database"; fi
if [ -z "$dbUrl" ]; then info "Database URL not available"; else info info "Database URL :- $databaseUrl"; fi
if [ -n "$escDatabaseUrl" ]; then info "Escaped database URL :- $escDatabaseUrl"; fi
if [ -z "$dbUsername" ]; then info "Username not available"; else info "Database Username :- $dbUsername"; fi
if [ -z "$dbDriver" ]; then info "Database driver not available"; else info "Database Driver :- $dbDriver"; fi
if [ -z "$dbDriverDir" ]; then info "Database driver directory not available"; else info "Database Driver Directory :- $dbDriverDir"; fi
if [ -z "$dbPassword" ]; then info "Password not available"; else info "Database password :- *******"; fi

info "Setting database properties..."
prop_replace 'nifi.registry.db.url'   "$escDatabaseUrl"
prop_replace 'nifi.registry.db.username'   "$dbUsername"
prop_replace 'nifi.registry.db.password'   "$dbPassword"
prop_replace 'nifi.registry.db.driver.directory'   "$dbDriverDir"
prop_replace 'nifi.registry.db.driver.class'   "$dbDriver"
prop_replace 'nifi.registry.db.directory'   "$dbDirectory"
prop_replace 'nifi.registry.db.url.append'   "$dbUrlAppends"

info "Database properties set."

delete_temp_files
