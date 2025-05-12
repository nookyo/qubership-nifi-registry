# Installation Guide

qubership-nifi-registry service can be started in docker (or compatible) container runtime with support of `docker compose` command.

Sections below describe typical startup configurations:
1. plain - service without any authentication with plain (HTTP) communications and file-based storage
2. tls - service with mTLS authentication with encrypted (HTTPS) communications and file-based storage
3. db - service with mTLS authentication, encrypted (HTTPS) communications and PostgreSQL storage
4. oidc - service with mTLS and OIDC authentication, encrypted (HTTPS) communications and PostgreSQL storage.

## Start

### Plain

Execute the following steps, to run service:
1. Set up environment parameters [`docker.env`](../dev/plain/docker.env)

| Parameter | Required | Default | Description                                                                                                                                                                                                                              |
|-----------|----------|---------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| BASE_DIR  | Y        | .       | Defines directory, where local volumes will be located. Subdirectories include:<ul><li>temp-vol/nifi-reg/database/ - for storing metadata (buckets, flows)</li><li>temp-vol/nifi-reg/flow-storage/ - for storing flow versions</li></ul> |

2. Change image in docker compose [`docker-compose.yaml`](../dev/plain/docker-compose.yaml), if needed. By default, `ghcr.io/netcracker/nifi-registry:latest` is used
3. Start docker compose

```shell
docker compose -f dev/plain/docker-compose.yaml --env-file dev/plain/docker.env up -d
```

### TLS

Execute the following steps, to run service:
1. Set up environment parameters [`docker.env`](../dev/tls/docker.env)

| Parameter           | Required | Default | Description                                                                                                                                                                                                                                                                                |
|---------------------|----------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| BASE_DIR            | Y        | .       | Defines directory, where local volumes will be located. Subdirectories include:<ul><li>temp-vol/nifi-reg/database/ - for storing metadata (buckets, flows)</li><li>temp-vol/nifi-reg/flow-storage/ - for storing flow versions</li><li>temp-vol/tls-cert/ - for TLS certificates</li></ul> |
| GIT_REPO_DIR        | Y        | .       | Defines directory, where qubership-nifi-registry repository is located locally.                                                                                                                                                                                                            |
| TRUSTSTORE_PASSWORD | Y        |         | Defines password for keystore with trusted certificates. It'll be created during the first run.                                                                                                                                                                                            |
| KEYSTORE_PASSWORD   | Y        |         | Defines password for keystore with server certificates. It'll be created during the first run.                                                                                                                                                                                             |

2. Change image in docker compose [`docker-compose.yaml`](../dev/tls/docker-compose.yaml), if needed. By default, `ghcr.io/netcracker/nifi-registry:latest` is used
3. Start docker compose

```shell
docker compose -f dev/tls/docker-compose.yaml --env-file dev/tls/docker.env up -d
```

### DB

Starts the following services:
1. qubership-nifi-registry
2. postgresql.

As well as one auxiliary container:
1. nifi-toolkit - to generate TLS certificates using Apache NiFi Toolkit.

Execute the following steps, to run service:
1. Set up environment parameters [`docker.env`](../dev/tls-db/docker.env)

| Parameter           | Required | Default | Description                                                                                                                                                                                                                            |
|---------------------|----------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| BASE_DIR            | Y        | .       | Defines directory, where local volumes will be located. Subdirectories include:<ul><li>temp-vol/pg-db/ - for storing PostgreSQL database with qubership-nifi-registry data</li><li>temp-vol/tls-cert/ - for TLS certificates</li></ul> |
| GIT_REPO_DIR        | Y        | .       | Defines directory, where qubership-nifi-registry repository is located locally.                                                                                                                                                        |
| TRUSTSTORE_PASSWORD | Y        |         | Defines password for keystore with trusted certificates. It'll be created during the first run.                                                                                                                                        |
| KEYSTORE_PASSWORD   | Y        |         | Defines password for keystore with server certificates. It'll be created during the first run.                                                                                                                                         |
| DB_PASSWORD         | Y        |         | Defines password for PostgreSQL database.                                                                                                                                                                                              |

2. Start docker compose

```shell
docker compose -f dev/tls-db/docker-compose.yaml --env-file dev/tls-db/docker.env up -d
```

### OIDC

Starts the following services:
1. qubership-nifi-registry
2. postgresql
3. keycloak.

As well as two auxiliary containers:
1. nifi-toolkit - to generate TLS certificates using Apache NiFi Toolkit
2. postgresql-init - to create keycloak database.

Execute the following steps, to run service:
1. Set up environment parameters [`docker.env`](../dev/oidc/docker.env)

| Parameter               | Required | Default | Description                                                                                                                                                                                                                            |
|-------------------------|----------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| BASE_DIR                | Y        | .       | Defines directory, where local volumes will be located. Subdirectories include:<ul><li>temp-vol/pg-db/ - for storing PostgreSQL database with qubership-nifi-registry data</li><li>temp-vol/tls-cert/ - for TLS certificates</li></ul> |
| GIT_REPO_DIR            | Y        | .       | Defines directory, where qubership-nifi-registry repository is located locally.                                                                                                                                                        |
| TRUSTSTORE_PASSWORD     | Y        |         | Defines password for keystore with trusted certificates. It'll be created during the first run.                                                                                                                                        |
| KEYSTORE_PASSWORD       | Y        |         | Defines password for keystore with server certificates. It'll be created during the first run.                                                                                                                                         |
| DB_PASSWORD             | Y        |         | Defines password for postgres user in local PostgreSQL database.                                                                                                                                                                       |
| KEYCLOAK_ADMIN_PASSWORD | Y        |         | Defines password for admin user in local keycloak instance.                                                                                                                                                                            |

2. Start docker compose

```shell
docker compose -f dev/oidc/docker-compose.yaml --env-file dev/oidc/docker.env up -d
```

## Usage

### Plain mode

Navigate to `http://localhost:18080/nifi-registry/` to access qubership-nifi-registry.

### TLS mode

Navigate to `https://localhost:18080/nifi-registry/` to access qubership-nifi-registry.
You'll be prompted to use client certificate for authentication.
See sections below on certificates configuration.

#### Import CA certificate

Self-signed CA certificate is generated in `$BASE_DIR/temp-vol/tls-cert/nifi-cert.pem`.
You can import it in your browser as trusted and then open new window or restart browser for changes to be applied.

#### Import client certificate

Client certificate is generated in `$BASE_DIR/temp-vol/tls-cert/CN=admin_OU=NIFI.p12` in PKCS12 (PFX) format.
Password for is available in `$BASE_DIR/temp-vol/tls-cert/CN=admin_OU=NIFI.password`.
To access qubership-nifi-registry, you need to import it as Personal certificate in your browser.
After that you may need to open new window or restart browser for changes to be applied.

### OIDC mode

Navigate to `https://localhost:18080/nifi-registry/` to access qubership-nifi-registry.
Click on Login link to log in via keycloak.
In this example, keycloak has preconfigured user `nifi-test-user` with temporary password = `changeit`.
On the first login, you'll be requested to change password.

You can also use client certificate for authentication (see section on TLS mode).
See *Import CA certificate* section on certificates configuration.