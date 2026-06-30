# OpenADR 2.0b VEN Service — Claude Code Context

## Project
QCharge EV Management OpenADR VEN microservice.
Handles Demand Response communication between utility VTN and EV chargers via OCPP 1.6.

## Stack
- Java 21, Spring Boot 3.4.5, Maven
- MySQL 8.4 + Flyway migrations
- Spring Data JPA, Lombok, MapStruct
- RestClient (not Feign) for VTN communication
- JAXB for XML marshalling (223 generated classes)

## Architecture
VTN (utility) ←→ OpenADR VEN (this service) ←→ EV Management System
(data-service, central-service, smart-charging-service)
↕ OCPP 1.6
EV chargers

## Key Packages
- `service/registration` — VEN registration with VTN
- `service/event` — Pull mode polling, DR event handling, signal parsing
- `service/report` — Telemetry reporting to VTN
- `service/opt` — Opt-in/opt-out scheduling
- `service/transport` — HTTP transport layer + retry
- `integration/ocpp` — OCPP stub (TODO Phase 9: real calls)
- `controller` — TestController (local/test profile only, for certification testing)
- `exceptions/` — OpenAdrTransportException, ApplicationLayerErrorCodes
- `model/oadr20b` — JAXB generated classes (DO NOT EDIT)
- `model/oadr20b/builders` — avob builders (DO NOT EDIT)
- `utility/Oadr20bPayloadIds.java` — venID/vtnID extraction per payload type

## Critical Rules
1. NEVER edit files in `model/oadr20b/` — generated code
2. NEVER edit files in `model/oadr20b/builders/` — avob code
3. All XML payloads MUST validate against OpenADR 2.0b XSD schema
4. Transport is SimpleHTTP Pull only — no XMPP, no Push
5. Security: TLS 1.2, RSA 2048, mTLS mandatory
6. Use `Oadr20bEiEventBuilders`, `Oadr20bEiReportBuilders` etc. for building payloads
7. Always use `Oadr20bJAXBContext.getInstance()` for marshal/unmarshal
8. Run `mvn test -Plocal` before committing
9. ALL outgoing payloads MUST be wrapped in `<oadrPayload><oadrSignedObject>`
   via Oadr20bFactory.createOadrPayload() — QualityLogic Test Harness rejects
   bare root elements with ClassCastException during conformance validation
10. TestController (`controller/TestController.java`) is `@Profile({"local","test"})`
    ONLY — must never be reachable in staging/prod

## OpenADR Conformance Rules (implemented)
- Rule 12: VEN MUST respond oadrCreatedEvent when responseRequired=always ✅
- Rule 21: Validate venID/vtnID — throws OpenAdrTransportException on mismatch ✅
- Rule 30: Randomize dtstart when startafter present (ThreadLocalRandom) ✅
- Rule 48: Application layer error codes via ApplicationLayerErrorCodes ✅
- Rule 109: Return error 460 for unsupported signal types ✅
- Rule 301: Send oadrRegisterReport on startup ✅
- Rule 312: oadrReport MUST include xcal:duration; oadrMaxPeriod MUST NOT
  exceed report duration ✅ (fixed for certification)
- Rule 331: METADATA report reportType/itemBase/readingType combinations:
  TELEMETRY_USAGE→usage/powerReal|energyReal/Direct Read,
  TELEMETRY_STATUS→x-resourceStatus/None/x-notApplicable ✅ (fixed for cert)
- Rule 405: oadrRequestEvent after new registration ✅
- Rule 406: Re-registration — erase VenReport + OptSchedule on new registrationId ✅
  ⚠️ KNOWN BUG: queryRegistration() may leak registrationId into subsequent
  register() call, causing test N1_0060 to fail (TH expects NO registrationID
  in a forced-new-registration request). Root cause not yet fixed — see TODO.
- Rule 450: Out-of-sequence modificationNumber → error 450 ✅
- Rule 463: VTN returns 463 → log diagnostic + throw exception ✅
- Rule 500: Continuous polling until oadrResponse (queue empty) ✅
- Rule 509: schemaVersion attribute MUST be included on EVERY payload
  including oadrPoll ✅ (fixed for certification — was missing on oadrPoll)

## Implemented Services Summary

### Registration (EiRegisterParty) ✅
- Optional oadrQueryRegistration (queryRegistrationOnStartup in properties)
- oadrCreatePartyRegistration → oadrCreatedPartyRegistration
- Re-registration with existing registrationId
- Rule 406: detects new registrationId → eraseReportAndOptData() @Transactional
- extractRequestedPollFrequency — reads oadrRequestedOadrPollFreq from VTN
- handleRequestReregistration, handleCancelPartyRegistration (VTN-initiated)
- TestController endpoints for VEN-initiated actions (cert testing only):
    - POST /test/cancel-registration → initiateCancelRegistration()
    - POST /test/force-new-registration → initiateForcedNewRegistration()
    - POST /test/reregister → register()

### Event (EiEvent) ✅
- EventPoller: start/stop/updatePollInterval, ReentrantLock, jitter
- pollUntilQueueEmpty — all 7 poll response types handled
- sendPoll() includes schemaVersion=2.0b (rule 509, fixed for cert)
- DrEventHandler:
    - Rule 30: randomized dtstart via applyStartAfterJitter() (testable static)
    - Rule 450: out-of-sequence modificationNumber → 450
    - Rule 109: unsupported signal → 460 + OPT_OUT
    - requiresCreatedEventResponse() — checks oadrResponseRequired=always
- EventValidationService: parseSignal() → LOAD_DISPATCH > ELECTRICITY_PRICE > SIMPLE
- EventOptDecisionService: signal-aware opt decision

### Report (EiReport) ✅
- TELEMETRY_USAGE + TELEMETRY_STATUS + METADATA reports
- ReportService.buildTelemetryUsageMetadataReport():
    - reportType=USAGE (not READING — fixed for rule 331)
    - withDuration() called (was missing — fixed for rule 312)
    - oadrMaxPeriod aligned ≤ duration for both rID descriptors
- ReportService.buildTelemetryStatusMetadataReport():
    - reportType=X_RESOURCE_STATUS, readingType=X_NOT_APPLICABLE (fixed for rule 331)
- ReportService.registerReportingCapabilities():
    - Top-level reportRequestID explicitly cleared (factory defaults to "0",
      TH conformance check requires it empty for metadata-only registration)
- ReportRequestHandler: full lifecycle (create/cancel/recurring/one-shot)
- Rule 345: reportToFollow=true → final report before cancel

### Opt (EiOpt) ✅
- oadrCreateOpt (optIn/optOut per event), oadrCancelOpt

### Transport ✅
- VtnTransportService:
    - send() wraps EVERY outgoing payload in OadrPayload/oadrSignedObject via
      JAXBElement + QName before marshalling (TH requires this for conformance
      validation — bare root elements cause ClassCastException on TH side)
    - typed convenience methods, unwrap via Factory, validateIds,
      checkApplicationLayerError
    - guards against empty VTN response body (throws clear exception instead
      of NPE in jaxb.unmarshal)
- RetryHandler: truncated binary exponential backoff (spec 9.1.7)
    - 5xx + connection errors → retry; 4xx → no retry
    - Delay: 1s → 2s → 4s → ... → max 60s
- HttpClientConfig: mTLS TLS 1.2, explicit cipher TLS_RSA_WITH_AES_128_CBC_SHA256
  (rule 67), chunked transfer disabled (spec 9.1.9)
- CertificateExpiryChecker: startup check, configurable warn/critical thresholds

### OCPP Integration (Stub) 🔲 TODO Phase 9
- OcppIntegrationService.applySignal() dispatches to:
    - applyLoadDispatch(eventId, kWLimit) — TODO
    - applySimple(eventId, level) — TODO
    - clearEvent(eventId) — TODO

## TODO (remaining)

### Certification — Active Test Harness Debugging (CURRENT FOCUS)
- Fix root cause: queryRegistration() must NOT leak registrationId into
  the next register() call — needed to pass N1_0060_TH_VTN_1
  (rule 406: forced new registration MUST NOT include registrationID)
- Verify TestController endpoints work end-to-end against Test Harness
  for N1_0030 (cancel registration) and N1_0070 (cancel negative scenarios)
- Continue working through testcases/pull/registerparty/ven/*.java:
  N1_0010 (query, needs queryRegistrationOnStartup=true), N1_0015 ✅ PASS,
  N1_0020 ✅ PASS, N1_0025 (venID preconfigured — pending), N1_0030 (cancel —
  pending endpoint), N1_0040, N1_0050, N1_0060 (pending fix above),
  N1_0065, N1_0070 (in progress), N1_0080
- After registerparty/ven/* all pass: move to testcases/pull/event/ven/,
  testcases/pull/report/ven/, testcases/pull/opt/ven/, testcases/pull/general/

### Phase 9 — OCPP Integration
- EvmsAdapter — Feign client to smart-charging-service
- OcppIntegrationService.applyLoadDispatch() — real SetChargingProfile OCPP 1.6
- OcppIntegrationService.applySimple() — map level 0/1/2/3 → charging limit
- OcppIntegrationService.clearEvent() — remove charging profile at event end
- MeterValuesCollector — read kW/kWh from OCPP → real telemetry reports
- EventOptDecisionService — real opt based on charger availability

### Final Certification Steps
- Receive signed VEN cert from eonti.com (CSR sent, awaiting response)
- Once all test cases pass against Test Harness: submit to QualityLogic
  for official certification

## Application Error Codes (exceptions/ApplicationLayerErrorCodes.java)
200  — OK
450  — Out of sequence
451  — Not allowed
452  — Invalid ID
453  — Not recognized
454  — Invalid data
459  — Compliance error other
460  — Signal not supported ← rule 109
461  — Report not supported
462  — Target mismatch
463  — Not registered/authorized
469  — Deployment error other

## Running locally
```bash
cd /Users/galya/IdeaProjects/qCharge && docker-compose up -d db
cd ~/mock-vtn && docker-compose up -d
export JAVA_HOME=/Users/galya/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home
cd /Users/galya/IdeaProjects/qCharge/openadr-service
mvn spring-boot:run -Plocal
```

## Testing
```bash
export JAVA_HOME=/Users/galya/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home
mvn test -Plocal                                   # all unit tests
mvn test -Plocal -Dtest=RetryHandlerTest           # retry backoff
mvn test -Plocal -Dtest=ErrorHandlingTest          # rule 30, 48, 406, 463
mvn test -Plocal -Dtest=CertificateExpiryCheckerTest
mvn test -Plocal -Dtest=EventPayloadTest           # event + signal
mvn test -Plocal -Dtest=ReportPayloadTest          # report
mvn test -Plocal -Dtest=OptPayloadTest             # opt
mvn test -Plocal -Dtest=RegistrationPayloadTest    # registration
mvn test -Plocal -Dtest=PollPayloadTest            # poll
```

## Key Files
- `service/transport/VtnTransportService.java` — typed HTTP, oadrPayload wrap,
  retry, 463 check, empty-response guard
- `service/transport/RetryHandler.java` — exponential backoff (spec 9.1.7)
- `service/event/EventPoller.java` — full poll loop, all 7 response types,
  schemaVersion on oadrPoll
- `service/event/DrEventHandler.java` — rule 30/109/450, signal handling
- `service/event/EventValidationService.java` — parseSignal(), ParsedSignal record
- `service/event/EventOptDecisionService.java` — signal-aware opt decision
- `service/registration/RegistrationService.java` — full registration flow,
  rule 406 re-registration, VEN-initiated cancel/force-new (for cert testing)
- `service/report/ReportService.java` — register + metadata reports,
  duration/samplingRate/reportType fixes for conformance
- `service/report/ReportRequestHandler.java` — full VTN report lifecycle
- `service/opt/OptService.java` — oadrCreateOpt / oadrCancelOpt
- `controller/TestController.java` — manual trigger endpoints for cert testing
  (local/test profile only)
- `integration/ocpp/OcppIntegrationService.java` — OCPP stub (Phase 9)
- `exceptions/ApplicationLayerErrorCodes.java` — all 12 OpenADR error codes
- `exceptions/OpenAdrTransportException.java` — httpStatusCode, isClientError()
- `config/HttpClientConfig.java` — mTLS, TLS 1.2, cipher suite, spec 9.1.9
- `config/OpenAdrProperties.java` — all config including retry, cert expiry settings
- `utility/Oadr20bPayloadIds.java` — venID/vtnID extraction
- `model/oadr20b/Oadr20bFactory.java` — JAXB object creation + createOadrPayload()
  wrap + unwrap (getSignedObjectFromOadrPayload)

## Certificates
- Dev keystore: `src/main/resources/certs/keystore.p12` (pass: openadr-ven)
- Dev truststore: `src/main/resources/certs/truststore.p12` (pass: openadr-trust)
- Test truststore: `src/main/resources/certs/test/truststore.p12`
  (real OpenADR Alliance TEST CAs: Root CA + VEN CA + VTN CA from eonti.com)
- VEN cert CSR sent to security@eonti.com — waiting for signed cert

## Database
- MySQL 8.4, DB: openadr_service, user: test, pass: test, port: 3306
- Tables: ven_registration, dr_event, ven_report, opt_schedule
- Migrations: V1 (tables), V2 (opt_schedule), V3 (signal_name/type/value)
- ven_report has unique constraint on report_spec_id — saveCapability() should
  be upsert-style (find-or-create), not insert-only, to avoid duplicate key
  errors on repeated registration cycles during cert testing

## QualityLogic Test Harness (Certification)
- Location: ~/EclipseProjects/OpenADR20bCertTest_v1_1_7/
- Java 17 required (Corretto 17 works), Eclipse IDE for Java Developers
- Compiler compliance level: set to 17 in Eclipse (not 21)
- Properties file: src/properties/vendor.selftest.openadrconfig.properties
    - HTTP_VEN_BaseURL=http://127.0.0.1:8081 (matches our server.port)
    - HTTP_Security=HTTP_Basic_Or_No_Security (no TLS for now)
    - UseStaticVENID=false (TH accepts whatever venID our VEN sends)
- Run test cases directly (NOT via DUT_Register utility — that only runs
  abbreviated bootstrap, not full conformance flow):
  testcases/pull/registerparty/ven/N1_00XX_TH_VTN_1.java → Run As Java Application
  Click "Resume" on prompts (not Yes/No — these are PromptType dialogs)
- Then start our VEN: mvn spring-boot:run -Plocal
- Logs: ~/EclipseProjects/OpenADR20bCertTest_v1_1_7/log/TraceLog_*.txt
  and logfile.html — contains exact Conformance Validation Error messages
- Between failed test runs, clear DB to avoid duplicate-key / stale-state
  issues:
- docker exec -it $(docker ps | grep mysql | grep "3306" | awk '{print $1}')
  mysql -utest -ptest openadr_service
  -e "DELETE FROM ven_report; DELETE FROM ven_registration;
  DELETE FROM dr_event; DELETE FROM opt_schedule;"

### Certification Progress (testcases/pull/registerparty/ven/)
- N1_0015_TH_VTN_1 — ✅ PASS (poll interval check)
- N1_0020_TH_VTN_1 — ✅ PASS (full bootstrap: register→report→requestEvent→poll)
- N1_0025_TH_VTN_1 — in progress (venID preconfiguration check)
- N1_0030_TH_VTN_1 — pending (needs /test/cancel-registration endpoint)
- N1_0060_TH_VTN_1 — FAILED (registrationId leak bug, see TODO above)
- N1_0070_TH_VTN_1 — in progress

## Mock VTN (non-certification local dev)
- Location: ~/mock-vtn/, Port: 8080
- Sends LOAD_DISPATCH event (25kW) every 5th poll
- Rebuild: cd ~/mock-vtn && docker-compose down && docker-compose up --build
- NOTE: when running against QualityLogic Test Harness, stop mock-vtn first —
  TH also listens on 8080 and will conflict