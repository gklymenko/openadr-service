# Local Eonti test certificates

This directory is used only by the `local` Spring profile.

Expected local files (ignored for new additions and excluded from packaged JARs):

- `ven-identity-certification.p12`
- `truststore-certification.p12`

Provide their passwords through these local environment variables:

- `OPENADR_VEN_PRIMARY_IDENTITY_PASSWORD`
- `OPENADR_TRUSTSTORE_PASSWORD`

Do not place production certificates or unencrypted private keys here.
