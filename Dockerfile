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

FROM alpine/java:21-jdk as base
LABEL org.opencontainers.image.authors="qubership.org"

USER root
#add jq:
RUN apk add --no-cache \
    jq=1.7.1-r0 \
    bash=5.2.26-r0

ENV NIFI_REGISTRY_BASE_DIR /opt/nifi-registry
ENV NIFI_REGISTRY_HOME $NIFI_REGISTRY_BASE_DIR/nifi-registry-current
ENV NIFI_TOOLKIT_HOME ${NIFI_REGISTRY_BASE_DIR}/nifi-toolkit-current
ENV HOME=${NIFI_REGISTRY_HOME}

RUN chmod 664 /opt/java/openjdk/lib/security/cacerts \
    && adduser --disabled-password \
        --gecos "" \
        --home "${NIFI_REGISTRY_HOME}" \
        --ingroup "root" \
        --no-create-home \
        --uid 10001 \
        nifi-registry

USER 10001

FROM apache/nifi-registry:1.28.1 as nifi-reg2

RUN mkdir -p $NIFI_REGISTRY_HOME/persistent_data \
    && mkdir -p $NIFI_REGISTRY_HOME/persistent_data/flow_storage \
    && mkdir -p $NIFI_REGISTRY_HOME/persistent_data/database \
    && chown nifi:nifi -R $NIFI_REGISTRY_HOME/persistent_data \
    && chmod 774 -R $NIFI_REGISTRY_HOME/persistent_data \
    && rm -rf $NIFI_REGISTRY_HOME/ext/aws \
    && mkdir -p $NIFI_REGISTRY_HOME/conf-template \
    && mkdir -p $NIFI_REGISTRY_HOME/conf-template-custom \
    && mkdir -p $NIFI_REGISTRY_HOME/conf \
    && chmod 774 $NIFI_REGISTRY_HOME/conf \
    && mkdir -p $NIFI_REGISTRY_HOME/logs \
    && chmod 774 $NIFI_REGISTRY_HOME/logs \
    && mkdir -p $NIFI_REGISTRY_HOME/run \
    && chmod 774 $NIFI_REGISTRY_HOME/run \
    && mkdir -p $NIFI_REGISTRY_HOME/work \
    && chmod 774 $NIFI_REGISTRY_HOME/work
COPY --chown=nifi:nifi ./nifi-scripts/* $NIFI_REGISTRY_BASE_DIR/scripts/
RUN chmod 774 $NIFI_REGISTRY_BASE_DIR/scripts/*.sh

COPY --chown=nifi:nifi ./conf-template-custom/logback.xml ${NIFI_REGISTRY_HOME}/conf-template-custom/

RUN rm -rf $NIFI_TOOLKIT_HOME/lib/spring-web-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-core-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-aop-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-context-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-beans-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-expression-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-jdbc-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-tx-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-vault-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/spring-security-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/ant*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/netty-codec*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/xmlsec-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/h2-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/protobuf-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/esapi-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/nifi-toolkit-flowanalyzer-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/nifi-site-to-site-client-*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/velocity-engine-core*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/testng*.jar \
    && rm -rf $NIFI_TOOLKIT_HOME/lib/zookeeper*.jar \
    && rm -rf $NIFI_REGISTRY_HOME/lib/spp/json-smart-*.jar

RUN mkdir -p ${NIFI_REGISTRY_HOME}/ext-cached \
    && mkdir -p ${NIFI_REGISTRY_HOME}/utility-lib
COPY --chown=1000:1000 qubership-cached-providers/target/qubership-cached-providers-*.jar qubership-cached-providers/target/lib/*.jar ${NIFI_REGISTRY_HOME}/ext-cached/
COPY --chown=1000:1000 qubership-consul/qubership-consul-application/target/qubership-consul-application*.jar ${NIFI_REGISTRY_HOME}/utility-lib/qubership-consul-application.jar
COPY --chown=1000:1000 qubership-nifi-registry-deps/target/lib/json-smart-*.jar ${NIFI_REGISTRY_HOME}/lib/spp/json-smart-2.5.2.jar

FROM alpine/java:21-jdk as upd

USER root

RUN apk add --no-cache zip=3.0-r12 \
    && mkdir -p /tmp-upd \
    && chown 10001:0 /tmp-upd

USER 10001:0

RUN mkdir -p /tmp-upd/WEB-INF/lib/

COPY --chown=10001:0 --from=nifi-reg2 /opt/nifi-registry/nifi-registry-current/lib/nifi-registry-web-api-1.28.1.war /tmp-upd/
COPY --chown=1000:1000 qubership-nifi-registry-deps/target/lib/json-smart-*.jar /tmp-upd/WEB-INF/lib/
WORKDIR /tmp-upd
RUN jar -uf nifi-registry-web-api-1.28.1.war WEB-INF/lib/json-smart-2.5.2.jar \
    && zip -d nifi-registry-web-api-1.28.1.war WEB-INF/lib/json-smart-2.5.1.jar \
    && rm -rf WEB-INF/lib/json-smart-2.5.2.jar

FROM base
LABEL org.opencontainers.image.authors="qubership.org"

USER 10001:0
WORKDIR $NIFI_REGISTRY_HOME

COPY --chown=10001:0 --from=nifi-reg2 $NIFI_REGISTRY_BASE_DIR/ $NIFI_REGISTRY_BASE_DIR/
COPY --chown=10001:0 --from=nifi-reg2 $NIFI_REGISTRY_BASE_DIR/nifi-registry-current/conf $NIFI_REGISTRY_HOME/conf-template
COPY --chown=10001:0 --from=upd /tmp-upd/nifi-registry-web-api-1.28.1.war $NIFI_REGISTRY_HOME/lib/

RUN mkdir -p $NIFI_REGISTRY_HOME/db_schema_gen
COPY nifi-registry-util/target/nifi-registry-util-*.jar $NIFI_REGISTRY_HOME/db_schema_gen/nifi-registry-util.jar
COPY nifi-registry-migration-util/target/nifi-registry-migration-util-*.jar  ${NIFI_REGISTRY_HOME}/lib/nifi-registry-migration-util.jar

COPY nifi-registry-db-util/target/lib/postgresql-*.jar ${NIFI_REGISTRY_HOME}/lib/

VOLUME ${NIFI_REGISTRY_HOME}/conf
VOLUME ${NIFI_REGISTRY_HOME}/logs
VOLUME ${NIFI_REGISTRY_HOME}/run
VOLUME ${NIFI_REGISTRY_HOME}/work

EXPOSE 18080 18443
# Start NiFi Registry
#
# We need to use the exec form to avoid running our command in a subshell and omitting signals,
# thus being unable to shut down gracefully:
# https://docs.docker.com/engine/reference/builder/#entrypoint
#
# Also we need to use relative path, because the exec form does not invoke a command shell,
# thus normal shell processing does not happen:
# https://docs.docker.com/engine/reference/builder/#exec-form-entrypoint-example
#
# ENTRYPOINT overrides CMD defined in base image
ENTRYPOINT ["../scripts/start.sh"]
