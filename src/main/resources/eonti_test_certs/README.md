# Local Eonti test certificates

This directory is used only by the `local` Spring profile.

Expected local files (ignored for new additions and excluded from packaged JARs):

- `ven-identity-ecc.p12`
- `truststore-ecc.p12`

`ven-identity-ecc.p12` must contain:

- one ECC `PrivateKeyEntry`, normally with alias `openadr-ven`;
- the matching Eonti `DemoCertsEE.pem` device certificate;
- `DemoCertsICA.pem` as the intermediate certificate chain.

`truststore-ecc.p12` must contain at least the trusted Eonti
`DemoCertsRoot.pem` certificate as a certificate entry. It must not contain a
private key. The Test Harness must send its server intermediate certificate as
part of the TLS handshake; add that intermediate to this truststore only if the
Harness does not send it.

The Spring Boot SSL bundle is configured under
`spring.ssl.bundle.jks.openadr`. It selects the alias and creates the TLS
key/trust managers and `SSLContext`; the application validates only the
OpenADR-specific ECC certificate policy and expiry state.

Provide their passwords through these local environment variables:

- `OPENADR_VEN_PRIMARY_IDENTITY_PASSWORD`
- `OPENADR_VEN_PRIMARY_IDENTITY_ALIAS` (optional; defaults to `openadr-ven`)
- `OPENADR_TRUSTSTORE_PASSWORD`

Do not place production certificates or unencrypted private keys here.
