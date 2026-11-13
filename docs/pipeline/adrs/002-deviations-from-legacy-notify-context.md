# ADR 002 — Deviations from the legacy `cpp-context-notification-notify` context

**Status:** Accepted (living register — append rows as deviations are found)

## Context

`cp-gov-uk-notify-gateway` is the Modern-by-Default (Spring Boot) replacement for the legacy
WildFly/CQRS context service `cpp-context-notification-notify`. It is a re-platforming, not a
re-specification, so the default expectation is behavioural parity with legacy. Where the new
service **intentionally** or **structurally** diverges from legacy behaviour, we record it here
so reviewers can tell a deliberate change from an accidental regression, and so the parity gaps
introduced by the framework swap are visible in one place.

The two framework swaps that drive most deviations:

- **Messaging/eventing:** legacy CQRS command/event bus over Artemis (`uk.gov.moj.cpp.*`
  framework, RAML-defined command & query APIs) → Spring Boot + Azure Service Bus.
- **Async job scheduling:** legacy jobstore (`uk.gov.moj.cpp.jobstore`) → new
  `cp-task-manager` (`uk.gov.hmcts.cp.taskmanager`). The two have **different retry SPIs**
  (see [Retry exhaustion implementation](#retry-exhaustion-implementation)).

## Deviation register

| # | Area | Legacy | NG | Deliberate? | Client impact |
|---|------|--------|----|-------------|---------------|
| [D1](#d1-send-email-ingress) | Send-email ingress | Synchronous REST command API | Asynchronous Service Bus queue `ng-send-email`; no business HTTP endpoints | Yes | Yes |
| [D2](#d2-email-delivery-status-classification) | Email delivery-status classification | Fails only `technical-failure`, `permanent-failure`, `virus-scan-failed`; re-polls every other status until retries run out | Re-polls only in-flight statuses; every other status fails at once; poll `404` is permanent | Yes | No |
| [D3](#d3-attachment-reference) | Attachment reference | `fileId` (file-service) or `materialUrl` | `fileUri` (direct Azure Blob URI) or `materialUrl` | Yes | Yes |
| [D4](#d4-blob-host-allow-list) | Blob host allow-list | Blob endpoint pinned by config; no host check | `fileUri` host must be on a configured allow-list | Yes — new control | Yes |
| [D5](#d5-govuk-notify-egress-proxy) | GOV.UK Notify egress proxy | Notify reached through the corporate proxy | Direct egress; proxy can be enabled per environment | Yes | No |
| [D6](#d6-query-api-removed) | Query API removed | RAML query API over the view store | No query API; HTTP is actuator-only | Yes — removal | Yes |
| [D7](#d7-materialurl-resolution) | materialUrl resolution | Fetches the link as given | Material host only; `localhost` links rewritten; anything else refused | Yes | No |
| [D8](#d8-sas-document-download) | SAS document download | Opens any link Material returns, follows redirects | `https` Azure Blob links only, no redirects, no credentials, 15 MB cap | Yes — new control | No |
| [D9](#d9-office-365-send) | Office 365 send | Unreadable `200`: unhandled exception, job re-runs every hour and **sends the email again** each time | Unreadable `200` = SENT; SENT recorded by a separate task; calls time out | Yes | No |
| [D10](#d10-office-365-routing) | Office 365 routing | Office 365 for attachments over 2 MB **or** listed recipient domains | Office 365 for attachments over 2 MB only | Yes | No |
| [D11](#d11-send-letter) | Send letter | REST command; any `letterUrl` host read; final validation failure has no `clientContext` | Service Bus queue `ng-send-letter`; Material links only; `clientContext` on every failure | Yes | Yes |
| [D12](#d12-letter-status-polling) | Letter status polling | Fails only `technical-failure`, `permanent-failure`, `virus-scan-failed`; re-polls every other status | Re-polls only in-flight statuses; every other status fails at once | Yes | No |
| [D13](#d13-result-events) | Result events | `public.notificationnotify.events.notification-sent` / `-failed`, published to the **public event topic** for any subscriber | `notification-sent` / `notification-failed`, sent to the requester's **`ReplyTo` queue** only | Yes | Yes |
| [D14](#d14-request-validation-rejection) | Request validation rejection | Invalid request rejected synchronously by the REST command API with `400`; nothing queued | Request that cannot be read or fails validation answered with a **`notification-validation-failed`** event to `ReplyTo`; a message **without a `ReplyTo`** dead-lettered | Yes — consequence of D1 | Yes |

**Client impact** is **Yes** when a calling context must change its integration (transport, queue, field name or meaning, permissions, result-event name or delivery); **No** when the difference is internal to NG and the request and result contract are unchanged. What each client must do: [NG client onboarding](../../client-onboarding.md).

Not deviations (legacy parity is kept, only the implementation differs): retry exhaustion
([Retry exhaustion implementation](#retry-exhaustion-implementation)), the Material document upload
(`prepareUpload(bytes, filename)`), the [Material system user](#material-system-user) and
[Material and SAS failure classification](#material-and-sas-failure-classification).

## Deviation details

### D1. Send-email ingress

- **Legacy:** synchronous REST command API (`POST /notifications/{notificationId}`) in front of an async JMS handler.
- **NG:** Service Bus consumer (`SendEmailConsumer`); no REST command and no business HTTP endpoints (D6).
- **Why / risk:** fits the Modern-by-Default platform. Callers become fire-and-forget and read the outcome from the `ReplyTo` result events.
- **References:** `messaging/SendEmailConsumer.java`, `messaging/ServiceBusConfig.java`; legacy `notificationnotify-command-api.raml`.

### D2. Email delivery-status classification

**Email status returned by `GET /v2/notifications/{id}`** (meanings: [GOV.UK Notify status contract](#govuk-notify-status-contract-basis-for-d2--why-the-deviation-is-safe)):

| Notify status | Legacy | NG | Changed? |
|---------------|--------|----|----------|
| `created` | in progress — re-poll | in progress — re-poll | no |
| `sending` | in progress — re-poll | in progress — re-poll | no |
| `accepted`, `received`, `pending-virus-check` (letter statuses) | in progress — re-poll | in progress — re-poll | no |
| `delivered` | SENT | SENT | no |
| `permanent-failure` | FAILED at once | FAILED at once | no |
| `technical-failure` | FAILED at once | FAILED at once | no |
| `virus-scan-failed` | FAILED at once | FAILED at once | no |
| `temporary-failure` | re-poll until retries run out, then FAILED | **FAILED at once** | **yes** |
| `validation-failed` | re-poll until retries run out, then FAILED | **FAILED at once** | **yes** |
| `not found` | re-poll until retries run out, then FAILED | **FAILED at once** | **yes** |
| `cancelled` or any unknown value (→ `unexpected`) | re-poll until retries run out, then FAILED | **FAILED at once** | **yes** |

**HTTP errors on the status poll** (legacy `FailureSelector`, NG `GovNotifyFailureClassifier`):

| Error | Legacy | NG | Changed? |
|-------|--------|----|----------|
| `400`, `413` | permanent | permanent | no |
| `404` `NoResultFound` (unknown id, or past the 7-day retention) | temporary — retried, then FAILED | **permanent — FAILED at once** | **yes** |
| `401`, `403` | temporary | temporary | no (kept on purpose) |
| `429`, `5xx`, any other HTTP status | temporary | temporary | no |
| non-HTTP error (status `0`) | permanent | **temporary when caused by an I/O failure** (the service could not be reached or did not respond, PEG-3720); otherwise permanent | **yes** |

Legacy lists the **failure** statuses and treats everything else as in progress; NG lists the **in-progress**
statuses and treats everything else as a failure (`Gov.Notify responded with status '<status>'`).

- **Why / risk:** no failure status can turn into `delivered` on a later poll; the docs' remedy for each is a new send, which a poll cannot do ([GOV.UK Notify status contract](#govuk-notify-status-contract-basis-for-d2--why-the-deviation-is-safe)). NG reaches legacy's end state (FAILED) at once instead of after the retry window.
- **References:** `sender/NotificationStatus.java`, `task/CheckEmailStatusTask.java`, `sender/GovNotifyFailureClassifier.java`; legacy `client/NotificationStatus.java`, `EmailStatusResponseProcessor`, `FailureSelector`.

### D3. Attachment reference

- **Legacy:** attachment by `fileId` (file-service) or `materialUrl`.
- **NG:** `fileUri`, a direct Azure Blob URI read with managed identity (filename = last segment of the blob name), or `materialUrl` (D7, D8). A non-blank `materialUrl` wins; a blank one counts as absent, as legacy.
- **Why / risk:** BYO file store replaces the central file-service. **Rollback:** an image without `materialUrl` support drops the field and sends the email without the document, so roll back only before producers send it or once queued jobs carrying it have drained.
- **References:** `contracts/command-send-email-notification.schema.json`, `command/SendEmailCommand.java`, `sender/EmailSender.java`, `task/SendEmailTask.java`.

### D4. Blob host allow-list

- **Legacy:** blob endpoint and container pinned in JNDI; nothing on the wire chose the host.
- **NG:** `fileUri` must be `https` with a host in `cp.notification.blob.allowed-hosts`, checked before a token is attached; otherwise `DisallowedBlobHostException` (permanent). An empty list fails closed (Azurite/connection-string mode exempt).
- **Why / risk:** a URI on the queue could otherwise send the managed-identity token anywhere (SSRF). No change for valid `fileUri`s.
- **References:** `blob/BlobHostValidator.java`, `blob/BlobClientFactory.java`.

### D5. GOV.UK Notify egress proxy

- **Legacy:** Notify reached through the corporate egress proxy (`gov.notify.proxy.*`, `outgoing.proxy.*`).
- **NG:** proxy support ported (`cp.notification.govnotify.proxy.*`) but **off by default**; direct egress.
- **Why / risk:** AKS pods have direct outbound 443. Direct egress must be confirmed per environment before go-live ([operating rule](#govuk-notify-egress-proxy--operating-rule-basis-for-d5)).
- **References:** `sender/GovNotifyConfig.java`, `application.yaml`.

### D6. Query API removed

- **Legacy:** RAML query API over the view store (`GET /notification/{notificationId}`, `GET /notification`), which also showed the interim `ATTEMPTED` status recorded on every send error.
- **NG:** no query API; the interim NG-S04 endpoints are removed. HTTP is actuator-only and deny-by-default. The row stays `QUEUED` until SENT or FAILED, and failed attempts are logged.
- **Why / risk:** the interim API exposed personal data (`send_to_address`), was unauthenticated and had no known consumer. Support reads use logs, the result events and `pgreadonly`. Breaking for any undeclared caller. Supersedes FR-009, FR-024, NG-S04, NG-S14.
- **References:** `web/SecurityConfig.java`; legacy `notificationnotify-query-api.raml`.

### D7. materialUrl resolution

- **Legacy:** fetches `materialUrl` as given (`localhost:8080` works only through the WildFly pods' Istio sidecar); a generic non-Material branch also exists.
- **NG:** `MaterialUrlResolver` accepts a link on `CP_NG_MATERIAL_BASE_URL`, rewrites a `localhost`/`127.0.0.1` `/material-query-api/` link onto it (refusing `..` segments, encoded or not), and refuses anything else with no call.
- **Why / risk:** reproduces the mesh route from the gateway pod, and keeps the system user ([Material system user](#material-system-user)) off non-Material hosts.
- **References:** `material/MaterialUrlResolver.java`, `material/MaterialClient.java`.

### D8. SAS document download

- **Legacy:** opens whatever `Location` Material returns, follows redirects, no size cap while reading.
- **NG:** `https` and a `.blob.core.windows.net` host only; no redirects; nothing added to the request (no token, `CJSCPPUID` or correlation id); bounded by timeouts; 15 MB cap enforced while reading; link never stored or logged.
- **Why / risk:** the SAS link is a bearer credential; this stops a bad answer steering the download, and keeps the gateway's identity out of it.
- **References:** `material/SasLinkValidator.java`, `material/SasDocumentFetcher.java`, `material/MaterialConfig.java`.

### D9. Office 365 send

**Office 365 function responses** (legacy `HttpConnectionHelper` + `FailureSelector`, NG `Office365Client`):

| Response | Legacy | NG |
|----------|--------|----|
| `200`, readable body | SENT | SENT (recorded by the `RECORD_SENT` task) |
| `200`, unreadable body | unhandled exception; the job re-runs every hour and **calls the function again — another email each time**, notification stays `QUEUED` | **SENT**, empty subject and body |
| `400`, `413` | permanent | permanent |
| any other non-200 | retried | retried |
| I/O error | retried | retried |
| timeout (connect 30s, request 300s) | no timeout | **retried** |
| request cannot be serialised | — | **permanent, no call** |

- **Unreadable `200`:** a `200` means the function has sent the email (the function replies `200` only after the SMTP send), so NG records SENT; legacy's parse exception escapes the jobstore, which re-runs the job hourly with no limit and sends the email each time.
- **`RECORD_SENT`:** SENT is recorded by a separate task, so a failure there is retried without calling the function again; after the last retry the job is **parked** (retries `0`) for manual replay, as legacy's dead-lettered `mark-as-sent`. Replay (DB write, needs approval): set `retry_attempts_remaining` above `0` and `assigned_task_start_time` to now; consumers may then see a duplicate `notification-sent`.
- **Timeouts:** legacy had none; a hung call would hold a DB connection and the job lock. 300s sits above the function's ~230s cut-off; a timed-out call the function completes can send twice on retry.
- **Also:** endpoint and key mandatory at startup; logs carry only the notification id and HTTP status (legacy logged the function key, recipient and personalisation). Routing: D10.
- **References:** `sender/Office365Client.java`, `sender/Office365ResponseReader.java`, `task/RecordSentTask.java`, ADR-004; legacy `HttpConnectionHelper`, `MicrosoftOffice365ClientService`.

### D10. Office 365 routing

- **Legacy:** Office 365 when the attachment is over 2 MB **or** the recipient domain is in `recipientDomainsToRouteThroughOffice365`.
- **NG:** over 2 MB only; the domain route is not implemented.
- **Why / risk:** every environment sets the domain list to `" "`, so no email changes route. If CJSM routing is ever needed it must be built in the gateway.
- **References:** `sender/EmailClientFactory.java`; legacy `EmailSender.shouldSendToOffice365Notify`.

### D11. Send letter

| Part | Legacy | NG |
|------|--------|----|
| Ingress | `POST /notifications/{notificationId}` with `application/vnd.notificationnotify.letter+json` (Drools: System Users / Online Plea System Users) | Service Bus command on its own queue **`ng-send-letter`** (`CP_NG_SERVICEBUS_LETTER_COMMAND_QUEUE`); same fields `{notificationId, letterUrl, postage, clientContext}` (`command-send-letter-notification.schema.json`); access control is the queue's RBAC |
| `letterUrl` | a Material link is followed through its `Location`; **any other URL is read directly** | **Material path only** (D7, D8, [Material system user](#material-system-user)): resolved onto the Material service, fresh SAS link fetched credential-less; any other host is **refused** (permanent, no call) |
| Final validation failure | `notification-failed` has **no `clientContext`** | `notification-failed` carries `clientContext`, like every other outcome |

Everything else matches legacy: postage, the precompiled-letter call, send-error retries ([Material and SAS failure classification](#material-and-sas-failure-classification)), the 5 resends on `validation-failed` (D12) and `notification-sent`.

- **Why / risk:** one queue per command (D1). Every letter producer (correspondence, progression, resulting) sends a Material link, so none is affected; a non-Material URL would now be refused. `clientContext` on the last failure is additive.
- **References:** `messaging/SendLetterConsumer.java`, `task/SendLetterTask.java`, `contracts/command-send-letter-notification.schema.json`; legacy `LetterSender`, `SenderErrorResponseProcessor`.

### D12. Letter status polling

**Letter status returned by `GET /v2/notifications/{id}`** (`CHECK_LETTER_STATUS`, then `CHECK_ACCEPTED_LETTER_STATUS`):

| Notify status | Legacy | NG | Changed? |
|---------------|--------|----|----------|
| `created`, `sending`, `pending-virus-check` | in progress — re-poll | in progress — re-poll | no |
| `accepted` | moves to (or stays on) the accepted-letter poll | moves to (or stays on) the accepted-letter poll | no |
| `received` | SENT | SENT | no |
| `validation-failed` | resend (status `REQUEUED`, new send job) up to **5** times, then FAILED `400` | same | no |
| `technical-failure`, `permanent-failure`, `virus-scan-failed` | FAILED at once | FAILED at once | no |
| `delivered` | re-poll until retries run out, then FAILED | **FAILED at once** | **yes** (a letter never reaches it) |
| `temporary-failure`, `not found` | re-poll until retries run out, then FAILED | **FAILED at once** | **yes** |
| `cancelled` or any unknown value | re-poll until retries run out, then FAILED | **FAILED at once** | **yes** |

Polls use `letter-status-durations-secs` then `letter-accepted-status-durations-secs`; each keeps its own final-attempt delay ([Retry exhaustion implementation](#retry-exhaustion-implementation)). The resend counter is in the job data.

- **Why / risk:** as D2 — no re-polled failure can become `received`, so legacy only failed later. A letter never reaches `delivered`.
- **References:** `task/LetterStatusPoller.java`, `task/CheckLetterStatusTask.java`, `task/CheckAcceptedLetterStatusTask.java`; legacy `LetterStatusResponseProcessor`, `InvalidRequestHandler`.

### D13. Result events

| | Legacy | NG |
|---|--------|----|
| Event names | `public.notificationnotify.events.notification-sent`, `public.notificationnotify.events.notification-failed` | `notification-sent`, `notification-failed`; plus the NG-only `notification-validation-failed` (D14) |
| Where they go | **public event topic** (`public.event` on Artemis, via `NotificationNotifyPublicEventProcessor`); every subscribing context receives them | the Service Bus queue named in the request's **`ReplyTo`**; only the requester receives them |
| Where the name is | JSON envelope metadata (`name`) | Service Bus message `subject` |
| Payload | `notification-sent` / `notification-failed` fields | unchanged |

- **Why / risk:** the `public.<context>.events.` prefix marks a CQRS framework event published to the shared public topic. NG sends point-to-point to the requester, so the prefix no longer means anything. Each consumer must read its own reply queue and route on the bare `subject`; a handler for the legacy name (e.g. staging-dvla's `@Handles("public.notificationnotify.events.notification-sent")`) will not receive NG's events. Legacy's other public events (`email-notification-bounced`, `poca-email-notification-received`) are not raised.
- **References:** `result/NotificationSentEvent.java`, `result/NotificationFailedEvent.java`, `result/ResultEventPublisher.java`, `contracts/notification-{sent,failed}.schema.json`; legacy `NotificationNotifyPublicEventProcessor`, `public-publications-descriptor.yaml`.


### D14. Request validation rejection

The only behaviour change from moving ingress from REST to Service Bus (D1, D11) is **how a bad request is answered**. Every failure after the request is accepted already ended asynchronously in legacy, and still does.

| Failure | Legacy | NG | Changed? |
|---------|--------|----|----------|
| No `ReplyTo` on the message (null or blank) | n/a — REST answers the caller directly | message **dead-lettered** on `ng-send-email` / `ng-send-letter` before it is read, with reason `MissingReplyTo`; nothing sent, nothing recorded, no result event | **yes** |
| Required field missing or invalid (`notificationId`, `templateId`, `sendToAddress`; `letterUrl` for letters; `replyToAddress` not an email) | **`400`** to the caller | **`notification-validation-failed`** event to `ReplyTo`, message completed; nothing sent, nothing recorded | **yes** |
| Body not valid JSON, or a field of the wrong type (e.g. `notificationId` not a UUID) | **`400`** to the caller | **`notification-validation-failed`** event to `ReplyTo`, message completed; nothing sent, nothing recorded | **yes** |
| Transient failure accepting the request (e.g. database unavailable), or sending `notification-validation-failed` | `5xx` to the caller, or the JMS handler retries and then dead-letters on Artemis | message **abandoned** and redelivered by Service Bus; dead-lettered once the queue's max delivery count is reached | no — async in both |
| Send or status-poll failure after acceptance (Notify, Office 365, Material, blob) | job retried, then `notification-failed` on the public topic (or Artemis DLQ for unhandled errors) | job retried, then `notification-failed` to `ReplyTo` (D13) | no |
| Duplicate `notificationId` | ignored, no second send | ignored, no second send | no |

`notification-validation-failed` is NG-only; legacy has no equivalent event. It is sent to the `ReplyTo` queue with Service Bus `subject` `notification-validation-failed` and this body:

```json
{
  "reason": "PAYLOAD_VALIDATION_FAILED",
  "details": "sendToAddress: must not be blank",
  "originalEventPayload": "{\"notificationId\":\"…\",\"templateId\":\"…\"}"
}
```

- `reason` is always `PAYLOAD_VALIDATION_FAILED`.
- `details` uses the Jakarta Bean Validation `ConstraintViolationException` form `field: message`. For a payload that was read but is invalid it lists every violation, comma-separated and sorted by field. For a payload that could not be read it is the JSON parser's message, prefixed with the field when the parser names one (e.g. `templateId: Cannot deserialize value of type java.util.UUID …`).
- `originalEventPayload` is the command body exactly as received, as a JSON string. The request was not accepted, so there is no `notificationId` of NG's to correlate on; the caller reads its own from this payload.

- **Why / risk:** a queue has no synchronous response, so the `400` becomes an event on the caller's reply queue, for a payload NG cannot read as well as one that fails validation. That needs the reply queue, so `ReplyTo` is now required: a message without one is dead-lettered before it is read, because neither a rejection nor a delivery outcome could be returned (before this change such a message was processed and its results dropped silently). Rejection runs entirely before ingestion, so a rejected command never opens a database transaction: the `ReplyTo` check, parsing and validation happen in the consumer, and the first database access is `NotificationIngestionService.ingest` (`@Transactional`), reached only by a valid command. If the event cannot be sent, the message is abandoned like any other transient failure: Service Bus redelivers it and dead-letters it at the queue's max delivery count, so no separate in-process retry is needed. A dead-lettered command can be told apart by its reason: `MissingReplyTo` (set by NG) or `MaxDeliveryCountExceeded` (set by Service Bus). Callers should still validate against `command-send-*-notification.schema.json` before sending, and the DLQ count of `ng-send-email` / `ng-send-letter` must be watched.
- **References:** `messaging/SendEmailConsumer.java`, `messaging/SendLetterConsumer.java`, `messaging/CommandMessageProcessor.java`, `messaging/ValidationFailureNotifier.java`, `result/NotificationValidationFailedEvent.java`, `contracts/notification-validation-failed.schema.json`, `contracts/command-send-email-notification.schema.json`, `contracts/command-send-letter-notification.schema.json`; legacy `notificationnotify-command-api.raml`.

## Material and SAS failure classification

**Material and SAS failures** (legacy `EmailMaterialDownloader` + `FailureSelector`, NG `MaterialAttachmentException.retryable`):

| Failure | Legacy | NG |
|---------|--------|----|
| `materialUrl` not on the Material host (D7) | fetched as given | **permanent, no HTTP call** |
| SAS link not `https` Azure Blob (D8) | opened as given | **permanent, no HTTP call** |
| Material `400`, `413` | permanent | permanent |
| Material `403`, `404`, `5xx`, any other non-200 | retried | retried |
| Material `200` without `Location` or `filename=` | unhandled exception escapes the task; the job is picked up again once its lock is an hour old, so it re-runs every hour and stays `QUEUED` | **retried, then FAILED** |
| Material timeout, I/O error, interrupt | retried | retried |
| SAS `400`, `403`, `404`, `5xx`, any other non-200 | retried (`IOException`) | retried |
| SAS redirect | followed | **not followed — retried** |
| SAS timeout, I/O error, interrupt | retried | retried |
| Document over 15 MB | permanent, after downloading the whole file | permanent, **stops reading early** |

Every status legacy classified is handled the same way. The refusals are the new controls of D7 and D8. The
Material `200` row is not a deviation either: legacy's hourly re-run comes from an unhandled exception, and NG
applies legacy's designed rule (retry on the configured durations, then FAIL) to it, as for retry exhaustion. When there is no HTTP
response, `notification-failed` omits `statusCode` where legacy sent `999`; the only consumer that reads it
(subscriptions) copies it when present, so this is not a deviation. Every retry asks Material for a fresh SAS link. Retries use `email-durations-secs`; exhaustion ends FAILED
with the last status ([Retry exhaustion implementation](#retry-exhaustion-implementation)).

- **Logging:** each failed stage writes one log line with stage, outcome, status and the exception **message only** (never the throwable, the Material host, the base URL, the system user or the SAS query).
- **Why / risk:** Same outcome as legacy for Material `400`/`413`, Material `403`/`404`/`5xx`, every SAS non-200 (including `400`, retried as legacy did) and over-size documents. Differences: (1) refused links, rejected SAS links, redirects and timeouts are new controls, so they have no legacy equivalent; (2) a `200` without `Location` or filename is retried and then FAILED, where legacy left the task to an unhandled exception that re-runs the job every hour; (3) the 15 MB check stops reading early, where legacy downloaded the whole file first. Exhaustion uses the final-attempt reschedule ([Retry exhaustion implementation](#retry-exhaustion-implementation))
- **References:** new: `material/MaterialAttachmentException.java`, `material/MaterialUrlResolver.java`, `material/SasLinkValidator.java`, `material/MaterialClient.java`, `material/SasDocumentFetcher.java`, `material/MaterialAttachmentDownloader.java`, `task/SendEmailTask.java`, `task/TransientFailurePolicy.java`; legacy: `cpp-context-notification-notify` `EmailMaterialDownloader`, `DocumentDownloadClient`, `FailureSelector`, `SenderErrorResponseProcessor`; `cp-task-manager` `JobsRepository` (pickup query), `TaskExecutor`

## Material system user

- **Legacy:** Legacy sends its own context system user (`SystemUsers.NOTIFICATION_NOTIFY`) as `CJSCPPUID` on the Material query, resolved through `ServiceContextSystemUserProvider` from the JNDI value backed by Vault `secret/<env>/<stack>/system_users_notificationnotify_password`
- **NG:** The gateway sends **the same account** (parity: no new user, no priming or Users & Groups change). Its UUID comes from env `CP_NG_SYSTEM_USER_ID`, mapped by Flux from Key Vault secret `CP-NG-SYSTEM-USER-ID` (a per-environment copy of the Vault value). It is **mandatory at startup**: the app refuses to start unless it is a UUID, and it is never logged. The Material base URL is not a secret: `application.yaml` defaults it to Material's in-namespace Service (`http://material-service-wildfly-app/material-service`, the same in every environment), overridable by `CP_NG_MATERIAL_BASE_URL`, and the app refuses to start if it is not an absolute http(s) URL
- **Why / risk:** Spring Boot pods have no JNDI/Vault binding, so the value must arrive via Key Vault. Failing fast makes a missing secret visible at deploy rather than as Material 403s. Risks: the Vault and Key Vault copies can drift (shows as 403s; mitigated by an equality check at rollout); Material's audit attributes gateway calls to notification-notify (the gateway's per-download log line is the attribution record); retiring legacy must keep the account and the Key Vault copy while the gateway uses them
- **References:** new: `material/MaterialProperties.java`, `material/MaterialSettings.java`, `material/MaterialConfig.java`; ADR-003 (decision to reuse the account); legacy: `cpp-context-notification-notify` `DownloadRestClient`, `ServiceContextSystemUserProvider`

## Retry exhaustion implementation

Legacy terminalised a notification through the jobstore's exhaust task. `cp-task-manager` has no
such hook: on exhaustion `TaskExecutor` re-persists the task with `retry_attempts_remaining = 0`,
and the `JobsRepository` pickup query never picks up a job with no retries left, so the run that
would see retries exhausted never happens. The gateway keeps legacy behaviour itself:

- `TransientFailurePolicy.retryOrFail` retries while more than one attempt remains.
- On the **last retry** it reschedules the same task as `INPROGRESS` with no retry, after the last
  retry duration and with `finalAttempt` in the job data. That keeps the remaining count, so the job
  runs once more. Every configured duration is used (first attempt plus one retry per duration).
- When retries are exhausted, or the final attempt fails, it marks the notification FAILED and
  completes the task.

It is applied at every in-progress retry site so no path can leave a notification stuck `QUEUED`:
`CheckEmailStatusTask.evaluate` (genuine in-flight status), `CheckEmailStatusTask.handlePollFailure`
(transient poll error), `SendEmailTask` (transient send, blob and Material errors), and the letter
tasks (`SendLetterTask`, `LetterStatusPoller`), each with its own retry durations (D12).

Remove the workaround once `cp-task-manager` runs that last job. Scope boundary: this covers only
terminalisation on exhaustion of the configured retry durations — the DLQ/dead-letter mechanism,
configurable max-attempts and alerting remain **NG-S11 (FR-020)** scope.

## GOV.UK Notify status contract (basis for D2 — why the deviation is safe)

Our `CheckEmailStatusTask` **re-`GET`s the delivery status of an already-sent notification id**
(`GET /v2/notifications/{id}`). It does **not** resend. That distinction is what makes eager
failure safe. The [GOV.UK Notify REST API](https://docs.notifications.service.gov.uk/rest-api.html)
defines each status separately. **It does not group them into "temporary" and "permanent"
failures**: `temporary-failure` and `permanent-failure` are two individual statuses, not categories,
and the docs do not say whether any failure status is final. The grouping below is ours, based on
what each status's documented meaning allows the gateway to do. Quotes are from the docs, checked
2026-11-03.

**Email statuses** (what `CheckEmailStatusTask` sees):

| status | Notify docs say | can a re-poll of the same id still reach `delivered`? | our handling |
|--------|-----------------|------------------------------------------------------|--------------|
| `created` | "placed the message in a queue, ready to be sent to the provider"; "should only remain in this state for a few seconds" | yes — in flight | in-progress, re-poll |
| `sending` | "sent the message to the provider"; "the provider will try to deliver the message to the recipient for up to 72 hours"; Notify "is waiting for delivery information" | yes — in flight | in-progress, re-poll |
| `delivered` | "The message was successfully delivered." | — | success → `markSent` |
| `permanent-failure` | "the email address was wrong"; "remove these email addresses from your database" | no — the address is wrong | fail → `markFailed` |
| `temporary-failure` | "The provider could not deliver the message", e.g. "the recipient's inbox is full or their anti-spam filter rejects your email"; check the content does not look like spam "before you try to send the message again" | no — the docs' remedy is to **send again**, i.e. a new notification | fail → `markFailed` |
| `technical-failure` | "a problem between Notify and the provider"; "You'll have to try sending your messages again." | no — the message was never sent; the remedy is a **new send** | fail → `markFailed` |

So, from the poll's point of view:

- **In flight (re-poll):** `created`, `sending`. The gateway's in-progress set also holds `accepted`,
  `received` and `pending-virus-check`, which are letter statuses (below) and do not occur for email.
- **Temporary (the recipient might accept a later, new message):** `temporary-failure`, and
  `technical-failure` for email. "Temporary" describes the recipient or the provider, not this
  notification: the docs' remedy for both is to send the message again. A status poll cannot resend,
  so the gateway records FAILED with `Gov.Notify responded with status '<status>'` and leaves any
  resend to the caller (a resend feature is out of scope; legacy had none either).
- **Permanent (a new message would fail too):** `permanent-failure` — the address is wrong.
- **Anything else:** not an email status in the docs. `fromStatus` maps an unknown value to
  `unexpected`, which is not in-progress, so it fails.

**Letter statuses** (what `LetterStatusPoller` sees, D12), from the letter and precompiled-letter tables:

| status | Notify docs say | our handling |
|--------|-----------------|--------------|
| `pending-virus-check` | Notify "has not completed a virus scan of the precompiled letter file" | in-progress, re-poll |
| `accepted` | Notify "has sent the letter to the provider to be printed" | in-progress, moves to the accepted-letter poll |
| `received` | "The provider has printed and dispatched the letter." | success → sent |
| `validation-failed` | content "outside the printable area", or the last address line "is not a UK postcode or another country" | resend, up to 5 times, then FAILED `400` (D12) |
| `virus-scan-failed` | Notify "found a potential virus in the precompiled letter file" | fail — permanent, the file is the problem |
| `permanent-failure` | "The provider cannot print the letter. Your letter will not be dispatched." | fail — permanent |
| `technical-failure` | "a problem between Notify and the provider"; "Do not try to send the letter again." | fail — permanent for letters: the docs say **not** to resend |
| `cancelled` | "Sending cancelled. The letter will not be printed or dispatched." | fail — not in the in-progress set |

`technical-failure` means "send again" for email but "do not send again" for letters. Neither allows a
re-poll to succeed, so the gateway fails both at once.

**HTTP errors on the poll are classified separately** from the statuses above, by
`GovNotifyFailureClassifier.isTemporary`. **Permanent** (FAILED at once): `400`, `413`, and `404`
`NoResultFound`. Notify returns `404` for an unknown id **or** a message outside the retention period
("The default retention period is 7 days"; "If it's outside the retention period, you may no longer
get the status of the message"). A non-HTTP error is temporary when an I/O failure caused it (the
service could not be reached or did not respond, PEG-3720), and permanent otherwise. **Temporary** (retried on the configured durations, then FAILED): everything
else, including `401`, `403`, `429` and `5xx`.

**404 is classified permanent in `GovNotifyFailureClassifier`, not just here.** A `NoResultFound`
surfaces as a `GovNotifyException(404)` from `GovNotifyClient.checkStatus` (and, on the send path,
would never occur), so it reaches `CheckEmailStatusTask.handlePollFailure` → `isTemporary`. The
classifier therefore treats `404` as **permanent** alongside `400`/`413` — a re-poll can never make a
missing/expired id reappear, so retrying it for the whole ~7.6h window is futile. This diverges from
legacy `FailureSelector` (permanent set was only `{0, 400, 413}`; `404` was temporary), but is what
makes the "404 → fail" handling actually hold on the poll path. **`401`/`403` deliberately
stay temporary** (legacy parity): a Notify auth/permission failure is almost always a
credential/config error, and the multi-hour retry window gives ops a chance to fix the key and
redeploy so the same queued message succeeds on a later retry — terminalising eagerly would throw that
recovery opportunity away.

**Why re-polling the failure statuses is futile.** The provider's delivery attempts, up to 72 hours,
happen while the status is `sending`. The docs do not state that a failure status is final, but none
of the failure statuses describes a message that is still being delivered, and the remedy each one
gives is to fix the address or content and **send a new message** — an operation this status-poll
task cannot and must not perform. Therefore, for the poll, every status that is not `delivered` and
not in flight (`created/sending/accepted/received/pending-virus-check`) is terminal.

**Safety of the deviation (D2):** classifying these as terminal changes *when* we conclude
failure, not *whether* — legacy also ended these notifications in failure, just slowly (via retry
exhaustion) or, in the ported code before the retry-exhaustion fix, never (the hot-loop). Eager failure reaches the
same terminal outcome immediately, with an accurate reason, and — crucially — is what lets the
in-task exhaustion handler ([Retry exhaustion implementation](#retry-exhaustion-implementation)) apply only to the genuinely in-flight statuses. No notification that
legacy would have delivered is failed by this change, because Notify's own delivery attempts are
already complete before any of these statuses is returned. Defining in-progress as a **positive
allow-list** also means any future/unknown Notify status fails safe (terminal) rather than
re-polling forever.

## GOV.UK Notify egress proxy — operating rule (basis for D5)

The legacy per-environment proxy values (`gov.notify.proxy.*`, `outgoing.proxy.*`) are internal infra
hostnames held in `cpp-aks-deploy` `ansible/group_vars` (the source of truth) — **not** duplicated in
this repo (the org secrets scanner blocks internal URLs). The MbD service treats the proxy as an
opt-in fallback:

1. **Default off.** `application.yaml` defaults `cp.notification.govnotify.proxy.enabled=false` (host
   empty, port `0`), so no overlay config is needed — the service connects to Notify directly.
2. **Verify egress on onboarding.** Run the deployment sanity (`ng-asb-test.sh` roundtrip); a
   successful send + terminal result event proves direct egress — keep the proxy off.
3. **Enable only on failure.** If sanity fails because outbound 443 to `api.notifications.service.gov.uk`
   is blocked, set `CP_NG_GOVNOTIFY_PROXY_ENABLED=true` with the `HOST`/`PORT` from `outgoing.proxy.*`
   in `cpp-aks-deploy` (confirm with networking) in that stack's overlay and re-run sanity — per
   environment, no image rebuild.

## Consequences

- **Retry exhaustion** keeps legacy parity, so it is not a deviation. Do not remove the
  final-attempt workaround until `cp-task-manager` runs a job with no retries left; see
  [Retry exhaustion implementation](#retry-exhaustion-implementation).
- **D2 is an accepted, documented divergence** — reviewers should not "restore legacy parity"
  by reverting it. It is correct on the GOV.UK Notify contract and is what keeps
  terminal statuses off the retry-exhaustion path.
- **D1** is an accepted platform-level change; downstream integrators must move to the
  fire-and-forget model and read the outcome from the result events (there is no query API — D6).
- **D13** changes the result-event names and delivery: consumers read `notification-sent` /
  `notification-failed` from their own `ReplyTo` queue, not `public.notificationnotify.events.*` from the topic.
- **D14** follows from D1: an unreadable or invalid request is answered with a `notification-validation-failed`
  event on `ReplyTo` instead of a `400`; nothing is recorded. `ReplyTo` is mandatory; a message without one
  is dead-lettered (reason `MissingReplyTo`), and one whose event cannot be sent is redelivered until the
  queue's max delivery count dead-letters it. Callers handle the new event and
  alert on the command queues' DLQ count.
- **D7 and D8** are deliberate: the Material path reaches the same outcome as legacy (the Material document
  attached to the email) without the WildFly-only `localhost` route, without sending the system user or the
  gateway's identity anywhere but Material, and without following the SAS link off Azure Blob Storage.
- **Material and SAS failure classification** keeps legacy's retry-or-fail outcome for every
  status legacy handled, so it is not a deviation.
- **D9** keeps legacy's Office 365 call (ADR-004), records an unreadable `200` as SENT, and records SENT in a
  separate `RECORD_SENT` task (legacy: the `mark-as-sent` command), so a failure there is retried and finally
  parked for manual replay, never resent. A parked `RECORD_SENT` job (retries remaining `0`) is the
  signal to look for until NG-S11 adds a dead-letter state. **D10** routes to Office 365 on attachment size only; the recipient-domain route, which
  no environment enables, is not implemented.
- **D6** is a deliberate, breaking removal — reviewers should not re-add a query endpoint without its own
  authentication decision; any new HTTP endpoint is refused until explicitly permitted in `SecurityConfig`.
- **D5** is a deliberate default (proxy off), not a dropped capability — the proxy is ported and
  re-enablable per environment. Open item: egress verification per environment before go-live —
  sanity confirms direct egress, or the proxy is enabled per the operating rule above.
- New deviations discovered in later reviews should be appended as a one-line register row plus a
  details section (with a full legacy-vs-NG mapping table for any status change), rather than scattered
  across PR comments.

## Testing-strategy deviation — atomicity (NG-S02 DoD)

NG-S02's DoD named a `SendEmailConsumerIntegrationTest.Atomicity` scenario (forced failure between
INSERT and task-enqueue leaves neither row nor task, message not completed) as a DB-backed integration
test. We **deliberately do not** add that test — this is a conscious deviation from the DoD, not an
oversight. What the service actually owns is unit- and boundary-tested:

- **Rollback trigger (exception propagation)** — unit:
  `NotificationIngestionServiceTest.propagates_the_exception_when_enqueueing_the_send_email_task_fails`
  (repository `save` succeeds, `ExecutionService.executeWith` throws ⇒ `ingest` propagates; the test
  asserts the INSERT was attempted first, i.e. the failure occurs *after* the insert).
- **Message not completed on failure** — boundary IT:
  `SendEmailConsumerIntegrationTest.abandons_and_redelivers_the_message_when_ingestion_fails` (real ASB
  emulator: `ingest` throws ⇒ `context.abandon()`, redelivered, never completed).
- **Physical single-transaction rollback** — Spring `@Transactional(REQUIRED)`:
  `NotificationIngestionService.ingest` opens the transaction and `cp-task-manager`'s
  `JobService.insertJob` joins it. This is a framework guarantee and a code-review property, not
  application logic.

**Why:** a DB-backed rollback assertion verifies Spring's transaction API rather than our code; the
behaviours that depend on it are already covered above. Our test strategy keeps integration tests at
the boundaries and behaviour in the BDD suite (one shared cached context); a rollback-atomicity test
needs a different, transactional test-context bootstrap that adds build time to re-prove a framework
guarantee. Revisit only if a real rollback defect surfaces that a unit test could not have caught.
