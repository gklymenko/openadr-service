# openadr-service

OpenADR 2.0b uses XML-based messaging with a PUSH/PULL model and requires Mutual TLS for security.


cd src/main/resources/certs

# Add Root CA
keytool -import -alias openadr-root-ca \
-file root-ca.cer \
-keystore truststore.p12 \
-storetype PKCS12 \
-storepass openadr-trust \
-noprompt

# Add VTN CA
keytool -import -alias openadr-vtn-ca \
-file vtn-ca.cer \
-keystore truststore.p12 \
-storetype PKCS12 \
-storepass openadr-trust \
-noprompt

# Add VEN CA
keytool -import -alias openadr-ven-ca \
-file ven-ca.cer \
-keystore truststore.p12 \
-storetype PKCS12 \
-storepass openadr-trust \
-noprompt


# dev KeyStore (self-signed, поки немає реального VEN cert з https://www.eonti.com/openadrcerts#anchors-lf75qjxw1)
keytool -genkeypair \
-alias ven-identity \
-keyalg RSA \
-keysize 2048 \
-sigalg SHA256withRSA \
-validity 365 \
-keystore keystore.p12 \
-storetype PKCS12 \
-storepass openadr-ven \
-dname "CN=ven-dev-001, OU=EV-Management, O=QCharge, L=Kyiv, C=UA"

On eonti request DNS was fullfilled like "ds-prod-backend.qchargeapp.com"

