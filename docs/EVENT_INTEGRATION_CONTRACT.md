# OpenADR event integration contract (draft v1)

These contracts describe the future responsibilities of `data-service` and
`qCharge-charging-profile-service`. This patch does not implement either downstream service.

## Ownership

- `openadr-service`: OpenADR validation, event versions, schedules, interval transitions,
  opt decisions, retries and audit history.
- `data-service`: partners, locations, chargers, connectors, capabilities and mapping
  OpenADR targets to QCharge resources.
- `qCharge-charging-profile-service`: translating an accepted power plan to OCPP,
  profile ownership, charger acknowledgements, replacement and cleanup.
- Timestamps use UTC ISO-8601 instants; power uses integer watts.
- Commands are idempotent by `(eventId, modificationNumber, resourceId)`.

## Resolve targets through data-service

`POST /internal/openadr/v1/resources/resolve`

```json
{
  "eventId": "event-123",
  "modificationNumber": 2,
  "venId": "VEN-QCHARGE-1",
  "eventTargets": [
    {"type": "VEN_ID", "values": ["VEN-QCHARGE-1"]},
    {"type": "RESOURCE_ID", "values": ["location-42"]}
  ],
  "signalTargets": {
    "signal-load": [{"type": "END_DEVICE_ASSET", "values": ["EVSE"]}]
  }
}
```

```json
{
  "resolutionId": "1c1b6bd7-2811-4e0c-986d-05b2acb66f21",
  "resolvedAt": "2026-08-11T10:00:00Z",
  "resources": [{
    "resourceId": "charger-101",
    "locationId": 42,
    "chargerId": 101,
    "chargePointId": "D6239431C21C",
    "connectorIds": [1, 2],
    "timezone": "Europe/Kyiv",
    "smartChargingSupported": true,
    "supportedRateUnits": ["W"],
    "maxPowerWatts": 22000,
    "online": true
  }],
  "unresolvedTargets": []
}
```

Resolve resources before returning `optIn`. Use `462/optOut` for target mismatch and
`469/optOut` when a target matches but no executable resource can be resolved. Persist
the resolution snapshot with the event version.

## Apply or replace a charging profile

`PUT /internal/openadr/v1/events/{eventId}/resources/{resourceId}/profile`

```json
{
  "eventId": "event-123",
  "modificationNumber": 2,
  "resourceId": "charger-101",
  "chargePointId": "D6239431C21C",
  "connectorIds": [1, 2],
  "profilePurpose": "OPENADR_DR",
  "priority": 1,
  "validFrom": "2026-08-11T11:00:00Z",
  "validTo": "2026-08-11T11:30:00Z",
  "periods": [{
    "intervalUid": "0",
    "start": "2026-08-11T11:00:00Z",
    "end": "2026-08-11T11:15:00Z",
    "limitWatts": 11000,
    "sourceSignalId": "signal-load"
  }],
  "correlationId": "event-123:2:charger-101"
}
```

Return `200` for an idempotent replay and `202` for pending execution. A higher
modification atomically replaces the previous OpenADR-owned profile; an equal one is an
idempotent replay; a lower one returns `409 OUT_OF_SEQUENCE`.

## Clear a profile

`DELETE /internal/openadr/v1/events/{eventId}/resources/{resourceId}/profile?modificationNumber=2&reason=CANCELLED`

Reasons: `CANCELLED`, `COMPLETED`, `IMPLICIT_CANCELLATION`, `OPT_OUT`,
`MANUAL_OVERRIDE`. Only the profile owned by that OpenADR event may be cleared.

## Kafka messages

Topic `charging-profile.openadr.execution-status.v1`:

```json
{
  "messageId": "0be1d825-fd1c-4428-ac5f-d080ee66f31e",
  "occurredAt": "2026-08-11T11:00:02Z",
  "eventId": "event-123",
  "modificationNumber": 2,
  "resourceId": "charger-101",
  "executionId": "exec-789",
  "profileId": 870123,
  "status": "APPLIED",
  "errorCode": null,
  "errorMessage": null,
  "correlationId": "event-123:2:charger-101"
}
```

Statuses: `ACCEPTED`, `SENT_TO_CHARGER`, `APPLIED`, `REJECTED`, `TIMED_OUT`, `CLEARED`.

Topic `data-service.openadr.resource-change.v1`:

```json
{
  "messageId": "365c277a-10f0-4370-b13a-f3644f00ea76",
  "occurredAt": "2026-08-11T10:55:00Z",
  "resourceId": "charger-101",
  "changeType": "CAPABILITY_CHANGED",
  "online": false,
  "smartChargingSupported": true,
  "maxPowerWatts": 22000
}
```

Change types: `CREATED`, `UPDATED`, `DELETED`, `CONNECTIVITY_CHANGED`,
`CAPABILITY_CHANGED`.

## Delivery

- HTTP uses service authentication and bounded timeouts. Retry connection failures,
  `408`, `429` and `5xx`, but not validation failures.
- Kafka consumers deduplicate by `messageId`; producers use an outbox.
- Breaking payload changes create a `v2` endpoint or topic.
- Do not make downstream HTTP/OCPP calls inside the transaction that persists an event.
