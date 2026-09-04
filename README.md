# openadr-service

OpenADR 2.0b VEN implementation using HTTP Pull, TLS 1.2, and mutual TLS.

## Runtime environments

The same JAR and Docker image are used for every deployed environment. Spring profiles provide only environment-specific configuration.

| Environment | Spring profile | Certificates | Hostname verification |
|---|---|---|---|
| Developer machine with local Test Harness | `local` | Files under `src/main/resources/eonti_test_certs` | Disabled for the Test Harness certificate |
| Development EC2 used for OpenADR certification | `dev` | Eonti test PKCS#12 files from GitLab Variables | Disabled for the Test Harness certificate |
| Real production VTN | `prod` | Production PKCS#12 files from GitLab Variables | Enabled |

The hostname-verification exception applies only to the OpenADR HTTP client. Certificate-chain,
validity, mTLS client-authentication, TLS-version, and cipher checks remain enabled. The exception
must never be copied to the production profile.

## Local Test Harness

Expected files:

```text
src/main/resources/eonti_test_certs/
├── ven-identity-ecc.p12
└── truststore-ecc.p12
```

Set the local passwords without committing them:

```bash
export OPENADR_VEN_PRIMARY_IDENTITY_PASSWORD='<identity-password>'
export OPENADR_TRUSTSTORE_PASSWORD='<truststore-password>'
```

Configure the Test Harness with `HTTP_SHA256_Security`, start it as the VTN on port `8080`, and run:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The local VEN connects to `https://127.0.0.1:8080`. The Test Harness configuration file still uses an `http://` base URL because the tool changes the scheme automatically when security is enabled.

## Development/certification and production deployment

GitLab stores each PKCS#12 as single-line Base64. Create these variables twice, using the same names and different environment scopes:

| Variable | Type | Scopes |
|---|---|---|
| `OPENADR_VEN_PRIMARY_IDENTITY_P12_B64` | File, masked, hidden, protected | `dev`, `prod` |
| `OPENADR_VEN_PRIMARY_IDENTITY_PASSWORD` | Variable, masked, hidden, protected | `dev`, `prod` |
| `OPENADR_VEN_PRIMARY_IDENTITY_ALIAS` | Variable; optional, defaults to `openadr-ven` | `dev`, `prod` |
| `OPENADR_TRUSTSTORE_P12_B64` | File, masked, hidden, protected | `dev`, `prod` |
| `OPENADR_TRUSTSTORE_PASSWORD` | Variable, masked, hidden, protected | `dev`, `prod` |

The `dev` scope contains Eonti demo/test credentials used for certification.
The `prod` scope must contain a production VEN identity and production truststore.

Additional environment-scoped GitLab Variables required by deployment jobs:

```text
DEPLOY_HOST
DEPLOY_PORT                 # optional; defaults to 22
DEPLOY_USER
DEPLOY_SSH_PRIVATE_KEY      # File variable
DEPLOY_SSH_KNOWN_HOSTS      # File variable

DB_ADDRESS
DB_USERNAME
DB_PASSWORD
CHARGE_KEY
DATA_SERVICE_INNER_DOMAIN
AWS_S3_STATIC_URL           # optional

OPENADR_VTN_URL
OPENADR_VTN_ID              # optional until known
OPENADR_VEN_KEY             # stable logical VEN key; defaults to primary
OPENADR_VEN_ID
OPENADR_VEN_NAME            # optional

KAFKA_BROKERS               # required in dev and prod
CENTRAL_KAFKA_TOPIC         # central-service output topic; required in dev and prod
OPENADR_KAFKA_GROUP_ID      # optional; defaults to qcharge_openadr_<profile>
```

`OPENADR_VEN_KEY` scopes the enabled rows in `openadr_resource`. Event targeting and
report METADATA use the same `resource_id` values from that table. It must remain stable
when a VTN assigns or changes the protocol-level `venID`.

## OpenADR resource registry

Provision at least one enabled charge point through
`PUT /internal/openadr/v1/resources/charge-points/{chargePointPk}` before registering
reporting capabilities. A resource is assigned to the configured `OPENADR_VEN_KEY` and
receives the stable ID `qcharge-evse-{chargePointPk}`. Report registration fails fast
when a resource definition is invalid. If the active logical VEN has no enabled
resources, its METADATA catalog contains no telemetry capabilities.

## Per-resource telemetry pipeline

Every runtime profile consumes central-service messages from Kafka without
requiring a message key. METER_VALUE, CONNECTOR_STATUS, HEARTBEAT, PONG,
and DISCONNECTED messages are normalized and resolved by
chargePointId -> openadr_resource. Only enabled resources belonging to the
configured OPENADR_VEN_KEY are accepted.
OPENADR_KAFKA_GROUP_ID must remain distinct from data-service's consumer group,
otherwise Kafka would load-balance records between the two services.

Ordering does not depend on the Kafka partition. Ingestion locks the resource
row and compares source timestamps independently for power, energy, and
availability, so a late or duplicated message cannot overwrite newer state.
Power is stored in kW and Energy.Active.Import.Register in kWh. Connector 0 is
treated as the charger total; otherwise physical connector values are summed.

Normalized connector state, latest resource availability, and timestamped
resource snapshots are persisted by Flyway migration V7. OpenADR report
delivery queries those snapshots by ReportRequest.resourceId. Cleanup retains
the configured time window and at least the newest 100 samples per resource.
Kafka offsets are acknowledged only after the database transaction completes;
malformed contract messages are logged and skipped, while infrastructure or
database failures are retried.

Generate the `DEPLOY_SSH_KNOWN_HOSTS` value from a trusted workstation and verify the fingerprint before saving it in GitLab:

```bash
ssh-keyscan -H <ec2-host>
```

The pipeline decodes certificate variables, copies them to the EC2 host, and mounts them read-only into the container:

```text
/run/secrets/openadr/ven-identity.p12
/run/secrets/openadr/truststore.p12
```

Deployments are manual jobs on the default branch:

- `deploy dev`
- `deploy production`

Docker images are stored in the GitLab Container Registry under the immutable commit SHA tag.

## Certificate handling

Local Eonti test certificate files are excluded from the deployable JAR by Maven. No certificate file is copied into the Docker image.

The VEN declares only ECC security, which is permitted for a VEN by OpenADR rule 68 and the PICS (`RSA or ECC`)

Spring Boot's named `openadr` SSL bundle loads the PKCS#12 identity, truststore, key password, and key alias.

At startup the OpenADR-specific certificate health check requires:

- `ven-identity-ecc.p12`: one ECC private-key entry, the matching Eonti device certificate, and its intermediate certificate chain;
- `truststore-ecc.p12`: at least one trusted Eonti root certificate used to validate the Test Harness VTN certificate;
- the identity and truststore passwords;
- `OPENADR_VEN_PRIMARY_IDENTITY_ALIAS` when the private-key alias is not `openadr-ven`.

The device certificate must use a 256-bit-or-larger EC key. The identity chain must use X.509v3 and SHA-2 signatures; a hybrid chain may contain RSA CA certificates with keys of at least 2048 bits. Every identity-chain and truststore certificate must be currently valid. TLS is restricted to `TLSv1.2` and `TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256`.

For each future logical VEN, use a separate identity PKCS#12 and certificate fingerprint. The truststore may be shared when the VENs trust the same VTN PKI.

The application logs the OpenADR fingerprint and certificate expiration metadata during startup without logging private keys or passwords.

## Build

```bash
mvn clean verify
```

Build the image after Maven creates the JAR:

```bash
docker build -f docker/Dockerfile -t openadr-service:local .
```
