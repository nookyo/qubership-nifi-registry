# qubership-nifi-registry

qubership-nifi-registry is a fork of Apache NiFi Registry.
Compared with Apache NiFi Registry it supports:
1. additional environment variables for configuration
2. configuration parameters for PostgreSQL DB storage
3. migration process for moving from disk to PostgreSQL DB storage.

## Installation

1. Run qubership-nifi-registry container in docker:
```
docker run -e AUTH='none' -p 18080:18080 <image>
```
where `<image>` should be replaced with qubership-nifi-registry's image.


## User Guide

Apache NiFi Registry is a service providing version control, abilities to export/import for Apache NiFi flow. . Refer to Apache NiFi Registry [User Guide](https://nifi.apache.org/docs/nifi-registry-docs/html/user-guide.html) for basic usage.