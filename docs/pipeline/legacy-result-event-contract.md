# Legacy result-event contract (golden-master for FR-007 / FR-008)

Pulled verbatim from `cpp-context-notification-notify` on 2026-07-09. This is the **exact logical
contract** the MbD service must reproduce when it publishes a terminal result event to the inbound
message's `ReplyTo` queue (FR-007), and the golden-master FR-008 asserts against.

Source of truth (legacy):
- Publisher: `notificationnotify-event-processor/.../NotificationNotifyPublicEventProcessor.java`
- Schemas: `notificationnotify-event-processor/src/yaml/json/schema/public.notificationnotify.events.notification-{sent,failed}.json`

Only **two** of the legacy's four public events are in MVP scope (per FR-008): `notification-sent`
and `notification-failed`. `email-notification-bounced` and `poca-email-notification-received` belong
to the out-of-scope bounce/POCA flows.

> **Scope of this doc:** the **logical** payload contract (names, fields, required, parity). The
> **physical binding** — how these ride the ASB message (subject / applicationProperties / body),
> correlation/metadata propagation, the schema-owning repo, versioning — is an **Architecture & Design
> (Stage 2)** decision, not captured here.

---

## `public.notificationnotify.events.notification-sent`

`additionalProperties: false` — **no extra fields permitted**.

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `notificationId` | uuid | **yes** | |
| `sentTime` | date-time | **yes** | |
| `completedAt` | date-time | no | |
| `sendToAddress` | string | no | |
| `replyToAddress` | string (email) | no | |
| `emailSubject` | string | no | rendered template subject |
| `emailBody` | string | no | rendered template body |
| `clientContext` | string | no | |

**How legacy builds it:** the public payload is the internal `NotificationSent` event converted
**verbatim** (`objectToJsonObjectConverter.convert(payload)`) — a 1:1 copy. Metadata: event name set to
the string above, and `userId` set to the context system-user id when present.

## `public.notificationnotify.events.notification-failed`

`additionalProperties: false` — **no extra fields permitted**.

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `notificationId` | uuid | **yes** | |
| `failedTime` | date-time | **yes** | |
| `errorMessage` | string | **yes** | |
| `statusCode` | integer | no | added only when present |
| `clientContext` | string | no | added only when present |

**How legacy builds it:** hand-rebuilt field-by-field (NOT a verbatim convert) — always
`notificationId`/`failedTime`/`errorMessage`; `statusCode` and `clientContext` added **only if
present**. The internal event's `failedTask` field is deliberately **dropped**. No `userId` on metadata.

---

## Parity nuances (for FR-007 build + FR-008 assertion)

1. **`sent` carries `emailSubject`/`emailBody`/`replyToAddress`.** Our `SendEmailCommand` holds
   `templateId` + `personalisation`, not a rendered subject/body — source those from the Gov.UK Notify
   send response. Not `required`, so a minimal event is valid without them, but a true golden-master
   diff for a `clientContext`-bearing originator needs them populated.
2. **`failed` drops `failedTask` and omits absent optionals.** Reproduce the present-only-if-set logic
   for `statusCode`/`clientContext`; do not emit nulls.
3. **`additionalProperties: false` on both.** Add no fields (no `providerReference`, no
   `notificationType`). Any addition breaks parity.
4. **mi-reportdata MVP publishes neither.** No `ReplyTo` → no event. Exercised only via a synthetic
   `ReplyTo`-bearing message in tests until a real originator opts in.
   *Superseded by FR-025 / ADR-002 D14 (PEG-3697): `ReplyTo` is now required on every command, and a
   command without it is dead-lettered unprocessed. The NG-only `notification-validation-failed` event
   is outside this golden-master contract.*
