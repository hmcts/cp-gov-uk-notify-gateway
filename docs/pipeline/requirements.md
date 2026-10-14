# Requirements: cp-gov-uk-notify-gateway (notification-notify — Modern by Default rewrite)

> Pipeline stage 1 (Requirements). Source of truth for all downstream stages. **Human gate — not
> approved until the user confirms.** Grounded solely in the reviewed design/planning docs under
> `platform-engineering-knowledge-base/features/notification-notify-mbd-rewrite/` (implementation
> plans, design-and-architecture, infra-identity, integration-test-strategy, functionality analysis)
> and the HMCTS pipeline context docs (`tech-stack.md`, `hmcts-standards.md`, `logging-standards.md`,
> `azure-cloud-native.md`, `azure-sdk-guide.md`). Nothing here is invented — genuine gaps are listed
> under **Open questions**.

## Context

`cp-gov-uk-notify-gateway` is a new "Modern by Default" Spring Boot service that re-platforms the
legacy WildFly 26 / Java 17 CQRS + Event-Sourcing service `cpp-context-notification-notify`. It
dispatches notifications (emails, and — later — letters) on behalf of other Common Platform contexts,
sending via Gov.UK Notify or Microsoft Office 365 (APIM), tracking delivery status, and (optionally)
publishing a terminal result event back to the originating context. **The migration must not change
observable business behaviour** — same notification semantics, same retry/poll timings, same
result-event contract — while replacing the transport (Artemis/JMS + REST command API → Azure Service
Bus), the write model (CQRS/Event-Sourcing → direct-to-DB), the async engine (framework Jobstore →
cp-task-manager), the attachment store (legacy FileService → Azure Blob Storage BYO filestore), and
the deployment path (ADO/Helmsman → Flux CD GitOps).

This artefact covers **Implementation Epics 1–3** of that service, with the **mi-reportdata email
slice as the first client/integration** (MVP = Epic 1 + Epic 2; Epic 3 hardens it). Producer-side
changes inside `cpp-context-mi-reportdata` are **out of scope** and referenced only as the triggering
upstream dependency.

## Goals

- Deliver a Spring Boot 4.x / Java 25 service that consumes `send-*-notification` commands from an
  Azure Service Bus command queue and sends the notification, with no REST command API.
- Own idempotency/dedup, persistence (Postgres + Flyway), async retry/status-polling
  (cp-task-manager), and attachment retrieval from Azure Blob Storage.
- Authenticate to Azure Service Bus and Azure Blob **exclusively via managed-identity RBAC** — no SAS
  tokens, no connection strings anywhere in prod.
- Provide a read (query) REST API that returns all columns of the `notification` table.
- Optionally publish a terminal result event to the inbound message's `ReplyTo` queue, gated on
  `ReplyTo` presence; for the mi-reportdata MVP this is fire-and-forget (no `ReplyTo`).
- Prove the slice end-to-end for mi-reportdata email in STE, and harden it (failure/DLQ handling,
  observability, secured query API) without regressing the preserved result-event contract.

## Actors

| Actor | Description |
|-------|-------------|
| `cpp-context-mi-reportdata` (producer) | MVP first client. Uploads an NCES report CSV to its own Azure Blob container, then publishes a fire-and-forget `send-email-notification` command (with `fileUri`, `notificationId` = fresh UUID, **no** `ReplyTo`) to the ASB command queue. Producer-side changes are a dependency, not in scope. |
| Future originator contexts | Other CPP contexts (e.g. correspondence, progression, resulting, subscriptions, sjp) that will later send commands and opt in to result events purely by setting `ReplyTo`. Not implemented in these epics; shape must not preclude them. |
| Gov.UK Notify | External provider for standard emails (and later letters). Called to send and polled for delivery status. |
| Microsoft Office 365 (via Azure APIM) | External provider used when the recipient domain is `cjsm.net` (configurable) or the attachment is > 2MB; O365 responses are treated as immediately DELIVERED (no polling). |
| Query-API consumer | Machine/operator caller of the read REST API (returns all `notification` fields). No mi-reportdata consumer exists for the MVP, but the read API is **required regardless** (FR-009); concrete consumers and the auth model are settled during implementation and do not gate delivery. |
| Support / operations engineer | Monitors DLQ + metrics/logs, and queries notification status via the query API for incident triage. |
| Platform engineering team | Provisions the ASB namespace + queues and the managed-identity ASB RBAC role assignments (out-of-band Platform request, matching the Flux/idam model). |

## Functional requirements

> **Delivery order (milestones).** The original plan's **Epic 1 (bootstrap/wiring) and Epic 2 (E2E
> happy path) are combined** into the build sequence below; original Epic 3 becomes **Milestone 7**.
> FRs are **numbered in milestone / build order** (FR-001 → FR-024, read top-to-bottom). This ordering
> may change later if a milestone is resequenced; if it does, IDs will be re-run to match.
>
> **Repo references** (last column) are drawn from the implementation plan's epic tables and are meant
> to seed Jira ticket descriptions. **`svc`** = the service repo (`cp-gov-uk-notify-gateway`); **`←`** =
> pattern / port-from source; **(legacy)** = `cpp-context-notification-notify`. The **Jira** column records the created story key under epic [PEG-3350](https://tools.hmcts.net/jira/browse/PEG-3350) (blank = ticket not yet created).

### Milestone 1 — Bootstrap + Gov.UK Notify happy path (end-to-end, Gov.Notify only, attachments ≤ 2 MB)

Scaffold the service and deliver one complete send: ASB command in → dedupe/INSERT → cp-task-manager
`send-email` (+ `check-email-status`) → Azure Blob download → **Gov.UK Notify** → row `SENT`, with the
`ReplyTo`-gated result-publish path implemented (a no-op for the fire-and-forget mi-reportdata MVP).
Runs on the local emulator/Testcontainers stack. The ASB client is built on `DefaultAzureCredential`
from the outset (emulator connection string locally); the production **RBAC grants land in Milestone 5
(FR-013)**. **O365 routing is excluded** (Milestone 6) — until then a > 2 MB attachment cannot be
routed; see the note under Milestone 6.

| ID | Requirement | Priority | Repo references | Jira |
|----|-------------|----------|-----------------|------|
| FR-001 | Scaffold the service from the HMCTS Spring Boot template (Spring Boot 4.x, Java 25+, Gradle, actuator) and declare the required dependencies (ASB SDK, Blob SDK, azure-identity, cp-task-manager library, Flyway, Postgres driver, Gov.Notify client). | Must | • **svc**<br>• scaffold / house-style ← `cpp-mbd-idam-integration`<br>• persistence (JPA + Flyway) ← `cp-court-list-publishing-service`<br>• async engine ← `cp-task-manager` | PEG-3351 |
| FR-002 | Provide the Flyway migration (`V1000__create_notification_table.sql`, versioned ≥ 1000 to avoid colliding with cp-task-manager's `V1`/`V2`) for the `notification` table — finalised send-email columns: `notification_id` (UUID PK/dedup key), `notification_type` (TEXT, default `EMAIL`), `status` (TEXT, no CHECK), `send_to_address` (TEXT, nullable — legacy-faithful), `status_code` (INT), `error_message` (TEXT), `client_context` (TEXT, nullable — persisted on ingest and echoed into result events for parity, see FR-008), `result_queue` (TEXT, nullable — the inbound ASB `ReplyTo` message property persisted on ingest and read back at the terminal hop to route the result event, see FR-007; distinct from the Gov.Notify email `replyToAddress`), and non-null audit timestamps `created_at`/`updated_at` (`TIMESTAMP WITH TIME ZONE`); letter/bounce/POCA columns omitted — and provision the **cp-task-manager `jobs` table in the same datasource** so the row INSERT and task enqueue commit in one local transaction (FR-003). The `jobs` table is created automatically by cp-task-manager's `TaskManagerFlywayAutoConfiguration` — a `FlywayConfigurationCustomizer` (on by default, `taskmanager.schema.enabled`) that **merges its `classpath:db/taskmanager` migration (`V1__create_jobs_table.sql`) into the service's single Flyway run**; no manual `spring.flyway.locations` entry is needed. Drop the eventstore; no Liquibase. | Must | • **svc**<br>• Flyway / JPA pattern **and real cp-task-manager consumer** ← `cp-court-list-publishing-service` (also `cp-case-document-knowledge-service`)<br>• `jobs` schema + Flyway auto-config ← `cp-task-manager`<br>• viewstore reference ← (legacy) | PEG-3352 |
| FR-003 | **Listen & orchestrate command ingestion.** Consume `send-*-notification` commands from a **shared ASB command queue per command type** (e.g. `ng-send-email`) via a `ServiceBusProcessorClient` — a single shared queue per command type serves all clients (no per-client queue) and there is **no per-originator routing** (the optional `clientContext` field is a passthrough for result-event parity, not a routing key); **no REST command API** — using **`disableAutoComplete`** so messages are settled explicitly. Per command: **idempotent `notification_id` PK dedupe** (existing id → silent no-op, no row/no task), else **INSERT the `notification` row (`QUEUED`) and enqueue the cp-task-manager `send-email` task in one atomic local Postgres transaction** (cp-task-manager's `jobs` table co-located in the same datasource); then **`completeMessage()` (ack) only after that transaction commits**. A failure before commit is not completed — the message is `abandon`ed and redelivered, reaching the **DLQ** at `maxDeliveryCount`. (Settlement is the manual equivalent of JMS `CLIENT_ACKNOWLEDGE`: complete / abandon / dead-letter / defer.) | Must | • **svc**<br>• ASB consumer + managed-identity auth ← `service-cp-crime-hearing-results-document-subscription`<br>• async engine (co-located `jobs`) ← `cp-task-manager` | PEG-3353 |
| FR-004 | In the `send-email` task, **download the attachment from Azure Blob Storage by `fileUri`** using `azure-storage-blob` + Workload Identity (Storage Blob Data Reader, including cross-context read of mi-reportdata's container); a blob 403/404 must mark the notification failed (no infinite retry). | Must | • **svc**<br>• blob download (BYO filestore) ← `cpp-context-reference-data` (UC1) | PEG-3355 |
| FR-005 | Gov.UK Notify path: on a successful send, schedule a `check-email-status` cp-task-manager task (storing the Gov.Notify external reference id); poll status and on `DELIVERED` call `markSent()`. | Must | • **svc**<br>• Gov.Notify sender + status-poll logic to port ← (legacy)<br>• retry/poll pattern ← `service-cp-crime-hearing-results-document-subscription` | PEG-3356 |
| FR-006 | Run all async scheduling, retry/backoff, and status-polling through **cp-task-manager**, preserving the legacy retry-duration lists verbatim (email retry durations `60,300,1800,3600,7200,14400`, and the other lists for letters when in scope). The lists MUST be **externalised to configuration** (a property, default = the legacy values), overridable per environment without a redeploy — preserving the legacy `@GlobalValue` tunability (`notify.email.retry.threshold.durations`), not hard-coded constants. | Must | • **svc**<br>• async engine (library + example) ← `cp-task-manager`<br>• retry-duration lists ← (legacy) | PEG-3357 |
| FR-007 | Implement the **full result-publish path**: build the terminal result event and send it to the per-originator queue named by the inbound message's ASB `ReplyTo` property. Because the terminal event is emitted by a **later task execution** than ingest, `ReplyTo` is **captured at ingest into the `notification.result_queue` column** (not threaded through `job_data`, not re-read from the message) and **read back at the terminal hop** — where the row is already loaded for the state-transition guard. **Publish only when `result_queue` is present**; when absent (the mi-reportdata MVP case), complete the send silently with no result published. | Must | • **svc**<br>• ASB sender pattern ← `service-cp-crime-hearing-results-document-subscription` | PEG-3358 |
| FR-008 | **Preserve the result events' names and payloads** as the contract, now queue-routed to `ReplyTo` rather than broadcast to a topic (validated by golden-master parity against the legacy service). **In MVP scope only `notification-sent` and `notification-failed` apply** (`public.notificationnotify.events.notification-sent` / `…notification-failed`); the other two legacy events (`…email-notification-bounced`, `…poca-email-notification-received`) belong to the **out-of-scope** bounce/POCA flows and are preserved when those land. | Must | • **svc**<br>• result-event names/payloads (golden-master) ← (legacy) | PEG-3359 |
| FR-009 | Provide a **read (query) REST API** that returns **all columns** of the `notification` table (single-notification lookup and search). Reads only — the "no REST" rule applies solely to the command path. (Authentication ships in the MVP — see FR-024, pulled forward per Q3; the read API is never exposed unauthenticated.) | Must | • **svc**<br>• REST / JPA pattern ← `cp-court-list-publishing-service`<br>• query reference ← (legacy) | PEG-3360 |

### Milestone 2 — Integration tests (run locally)

| ID | Requirement | Priority | Repo references | Jira |
|----|-------------|----------|-----------------|------|
| FR-010 | Provide an emulator/Testcontainers-based **integration test harness** (Postgres + ASB emulator + Azurite + **WireMock stubbing Gov.Notify/O365** + Awaitility), **runnable locally with no external calls**, proving message-in → blob download → Gov.Notify stub → row `SENT` → result-queue message, plus retry, redelivery/idempotency, atomicity, and blob 403/404 paths. | Must | • **svc**<br>• test-harness patterns ← `service-cp-crime-hearing-results-document-subscription`, `cpp-mbd-idam-integration` | PEG-3361 |

### Milestone 3 — Validation pipeline (CI — GitHub Actions)

| ID | Requirement | Priority | Repo references | Jira |
|----|-------------|----------|-----------------|------|
| FR-011 | Establish the **CI validation pipeline as GitHub Actions** (mirroring `cpp-mbd-idam-integration`, workflows shipped by the HMCTS template) — **not** Azure DevOps. On every pull request run: build + unit/integration suites (FR-010) via the reusable `ci-build-publish.yml` (invoked by `ci-draft.yml`), PMD lint (`code-analysis.yml`), secrets scan (`secrets-scanner.yml`), and CodeQL/SBOM (`codeql.yml`); all gates must pass before merge. Release builds (`ci-released.yml`) validate the apiSpec version then build/publish. | Must | • **svc** (`.github/workflows/*`, template-shipped)<br>• GitHub Actions CI approach ← `cpp-mbd-idam-integration` | PEG-3362 |

### Milestone 4 — STE simulators / stubs

| ID | Requirement | Priority | Repo references | Jira |
|----|-------------|----------|-----------------|------|
| FR-012 | Provide **simulator/stub routing for Gov.UK Notify and Microsoft Office 365 (APIM) in the STE environment**, so end-to-end deployment testing exercises the real send paths without dispatching real emails. Decide per provider between a hosted sandbox/test-mode API key (Gov.Notify) and a deployed mock connector (O365/APIM); the per-provider choice is made during implementation of this FR. | Should | • **svc** (test / STE config)<br>• Gov.UK Notify sandbox / test-mode key<br>• O365 (APIM) connector stub — TBD | PEG-3363 |

### Milestone 5 — Deploy to STE (ops: managed identity, RBAC, database, Flux CD)

| ID | Requirement | Priority | Repo references | Jira |
|----|-------------|----------|-----------------|------|
| FR-013 | Authenticate all ASB access (consume + send) via **managed-identity RBAC** using `DefaultAzureCredential` (Azure Service Bus Data Receiver + Data Sender at **queue (entity)** scope — least-privilege; `ReplyTo` resolves to the single provisioned result queue). No SAS token, no connection string in prod (emulator connection string permitted for local/test only). | Must | • **svc** (auth code)<br>• RBAC role assignments ← `cpp-helm-chart` (`ccm-namespace`/`mi.yaml`), `cpp-aks-deploy` (`ccm_workload_identities`) | PEG-3364 |
| FR-014 | Provision the service's own per-context managed identity with RBAC: Azure Service Bus Data Receiver + Data Sender (queue (entity) scope), Storage Blob Data Reader on the attachment container(s), and Key Vault Secrets User (read-only) for non-ASB secrets (e.g. Gov.Notify API key). No ASB SAS secret in Key Vault. | Must | • `cpp-helm-chart` (`ccm-namespace`/`mi.yaml`)<br>• `cpp-aks-deploy` (`ccm_workload_identities` + Key Vault CSI)<br>• pattern ← `cpp-context-reference-data` (UC1) | PEG-3365 |
| FR-015 | Provision the ASB namespace + command/result queues and assign the two ASB role assignments to the managed identity (Platform request, matching the Flux/idam model). | Must | `cpp-helm-chart` (`ccm-namespace`, ASO `servicebus.azure.com` CRDs) — else Platform (out-of-band) / `cpp-module-terraform-azurerm-servicebus` (see OQ-1) | PEG-3366 |
| FR-016 | Provision the Postgres database/schema across environments via ops/IaC. | Must | • `cpp-aks-deploy` (env group_vars / priming)<br>• `devops` DB tooling | PEG-3367 |
| FR-017 | Deploy to STE via **Flux CD GitOps** using the shared `springboot-app` Helm chart (HelmRelease + values/identity/secretProvider in the Flux config repo); legacy `cpp-aks-deploy` (ADO + Helmsman) is not used. | Must | • `cpp-flux-config` (HelmRelease)<br>• `cpp-helm-chart` (`springboot-app` chart)<br>• `cpp-aks-deploy` (identity/RBAC + Key Vault CSI)<br>• pattern ← `cpp-mbd-idam-integration` | PEG-3368 |

### Milestone 6 — Office 365 send path

> **Dependency / risk:** the mi-reportdata NCES CSV **may exceed 2 MB**, and the routing rule sends
> > 2 MB (or `cjsm.net`) via O365 — until this milestone lands, only ≤ 2 MB Gov.Notify sends work.
> Sequenced ahead of hardening so large-report sends are covered before go-live; confirm typical NCES
> report size.

| ID | Requirement | Priority | Repo references | Jira |
|----|-------------|----------|-----------------|------|
| FR-018 | Route the outbound channel via `SenderFactory`: recipient domain ending in `cjsm.net` (configurable) **or** attachment > 2MB → Office 365 via APIM; otherwise → Gov.UK Notify. | Must | • **svc**<br>• SenderFactory routing to port ← (legacy) |
| FR-019 | Office 365 path: treat a successful APIM response as immediately DELIVERED and call `markSent()` directly, with **no** `check-email-status` task. | Must | • **svc**<br>• O365 sender branch to port ← (legacy) |

### Milestone 7 — Unhappy paths & hardening (original Epic 3)

| ID | Requirement | Priority | Repo references | Jira |
|----|-------------|----------|-----------------|------|
| FR-020 | Handle failure scenarios: transient failures retry via the retry-duration list (`markAttempted()` between attempts, no result event); permanent failures / exhausted retries call `markFailed()` then return the task COMPLETED (cp-task-manager has no FAILED/DLQ so never keep throwing); an inbound consume failure before commit routes the message to the **DLQ**. | Must | • **svc**<br>• DLQ handling / retry ← `service-cp-crime-hearing-results-document-subscription`<br>• async engine ← `cp-task-manager` |
| FR-022 | Ensure at-least-once safety: cp-task-manager task re-runs and ASB redelivery must not double-act — **guard every result-event publish on a real state transition** (QUEUED/ATTEMPTED → terminal), and for a mid-send crash recover by querying Gov.Notify **by reference** before re-sending. | Must | • **svc**<br>• idempotency patterns ← (legacy), `cp-task-manager` |
| FR-023 | Emit **metrics and structured JSON logs**, with DLQ monitoring on inbound + result queues, and a metric/log counter for dropped `NotificationMonitor` failures (the legacy monitor events are dropped, not re-published). | Should | • **svc**<br>• house patterns ← `cpp-mbd-idam-integration`, `cp-court-list-publishing-service` |
| FR-024 | Secure the read (query) API with authentication (IDAM). **Pulled into the MVP per Q3 — delivered alongside FR-009, not deferred to this milestone.** | Must | • **svc**<br>• auth pattern ← `cpp-mbd-idam-integration` |

## Non-functional requirements

| ID | Category | Requirement | Threshold |
|----|----------|-------------|-----------|
| NFR-001 | Behavioural parity | Preserve observable business behaviour vs the legacy service — result-event names/payloads, sender routing rules, and retry/poll timings unchanged. | Golden-master payload diff = zero differences; retry-duration lists byte-equal to legacy |
| NFR-002 | Security — auth to Azure | ASB and Blob access via managed-identity RBAC only. | Zero SAS tokens / connection strings in code, config, env vars, Helm values (emulator-only exception for local/test) |
| NFR-003 | Security — secrets | Non-ASB secrets (Gov.Notify API key, DB creds) sourced from Azure Key Vault via CSI; identity holds least-privilege Key Vault Secrets User (read-only), not KeyVaultAdministrator. | No secret committed to the repo; Key Vault only |
| NFR-004 | Security — logging / PII | No PII, secrets, tokens, connection strings, real case references, or full request/response bodies in logs. | Zero tolerance |
| NFR-005 | Security — data classification | Treat notification data (recipient addresses, attachments) as OFFICIAL-SENSITIVE. | All handling |
| NFR-006 | Security — OWASP | Service assessed against the OWASP Top 10. | No unresolved Critical/High |
| NFR-007 | Observability — logging | JSON logs to stdout via logstash-logback-encoder with `correlationId` + `requestId` MDC fields; root level INFO. | Every log line valid JSON; template logback config unmodified without ADR |
| NFR-008 | Observability — metrics/tracing | Micrometer/actuator metrics (`/actuator/prometheus`) and OpenTelemetry tracing to Azure Monitor; DLQ depth monitored. | Metrics + traces present for send + publish paths |
| NFR-009 | Reliability — idempotency | Duplicate command (same `notificationId`) and task re-runs produce a single row and no duplicate send / duplicate result event. | Exactly-once observable effect under at-least-once delivery |
| NFR-010 | Reliability — atomicity | The ingest "INSERT notification row + enqueue cp-task-manager task" commits atomically in one local transaction via the co-located datasource (no separate reconciliation needed). State-update + result-event publish is at-least-once, made safe by the transition guard (NFR-009). | No orphaned QUEUED row without a task |
| NFR-011 | Tech-stack conformance | Spring Boot 4.x, Java 25+, Gradle, Flyway, Postgres 16, `uk.gov.hmcts.cp.*`, scaffolded from the HMCTS template (deviation requires an ADR). | Matches `tech-stack.md` / template |
| NFR-012 | Test coverage | Unit ≥80% on new code; integration covers all AC happy paths + top failures; result-event parity golden-master; STE E2E for the mi-reportdata slice. | Per `hmcts-standards.md` test pyramid |
| NFR-013 | Coding in the open | Public repo from day one; owned by an `hmcts` GitHub team with admin; Conventional Commits; no direct commits to `main`. | Enforced before repo creation / merge |
| NFR-014 | Data protection | Notification table doubles as audit log; retention period must be defined and enforced; UK GDPR / DPA 2018 apply; subject-access supportable. | Retention period defined |
| NFR-015 | Accessibility (WCAG 2.1 AA) | This is a backend/messaging service with no public user-facing UI; WCAG 2.1 AA applies to user-facing output only, so it is not applicable here. If any human-facing UI is later added it becomes mandatory. | N/A (no UI) — re-assess if UI added |

## Acceptance criteria

> Grouped by FR (each anchors ≥1 AC), in **Milestone 1→7 order** matching the Functional requirements
> above. FR-001 (scaffold) and FR-016 (Postgres provisioning) are exercised by the Milestone 1 harness
> and the Milestone 5 deployment, so they carry no dedicated AC.

### FR-002 — Flyway `notification` + cp-task-manager `jobs` table migration
- AC-001: Given a clean database, when Flyway runs, then the `notification` table is created with the send-email columns (`notification_id`, `notification_type`, `status`, `send_to_address`, `status_code`, `error_message`, `client_context`, `result_queue`) and non-null audit timestamps (`created_at`, `updated_at`), and no eventstore/Liquibase objects are created.
- AC-001a: Given a clean database and the cp-task-manager library on the classpath, when the service starts, then its Flyway auto-config merges `db/taskmanager` into the single Flyway run and the `jobs` table is created in the same datasource — so an INSERT into `notification` and a `jobs` enqueue can commit in one local transaction (no manual `spring.flyway.locations` entry required).

### FR-003 — Listen & orchestrate command ingestion
- AC-002: Given a `send-email-notification` message on the shared `ng-send-email` command queue, when the service is running, then the `ServiceBusProcessorClient` consumes it and no REST command endpoint exists on the service.
- AC-003: Given two clients sending to the same command-type queue, when both messages are consumed, then both are processed through the same shared queue and handler — no per-client queue and no per-originator routing (`clientContext` is a passthrough for result-event parity, not a routing key).
- AC-004: Given a command with a `notificationId` not present in the table, when consumed, then a `notification` row is inserted with status `QUEUED`.
- AC-005: Given a command whose `notificationId` already exists, when consumed (redelivery or duplicate), then it is a silent no-op — no second row, no second task.
- AC-006: Given a new command, when it is processed, then the `notification` row insert and the `send-email` task enqueue commit together in one Postgres transaction; if either fails, neither persists.
- AC-007: Given a message being processed with `disableAutoComplete`, when the local transaction commits successfully, then the ASB message is completed (acked).
- AC-008: Given a processing failure before commit, when the transaction rolls back, then the message is not completed — it is abandoned and eligible for redelivery/DLQ.

### FR-004 — Attachment download from Azure Blob
- AC-009: Given a command carrying a `fileUri`, when the `send-email` task runs, then the attachment is downloaded from Azure Blob via `azure-storage-blob` + Workload Identity and passed to `SenderFactory`.
- AC-010: Given a blob returning 403 or 404, when download is attempted, then the notification is marked FAILED and not retried indefinitely.

### FR-005 — Gov.UK Notify send + status poll
- AC-011: Given a successful Gov.Notify send, when the task completes, then a `check-email-status` task is scheduled and the Gov.Notify external reference id is carried in that task's payload (cp-task-manager `job_data`) — not persisted on the `notification` row. Crash recovery (FR-022) uses Gov.Notify's client `reference` = `notificationId`.
- AC-012: Given `check-email-status` polling returns `DELIVERED`, when the task runs, then `markSent()` is called and status becomes SENT.

### FR-006 — cp-task-manager with preserved timings
- AC-013: Given the email flow, when retry/poll intervals are inspected, then they match the legacy email retry-duration list `60,300,1800,3600,7200,14400` — sourced from the `cp.notification.retry.email-durations-secs` property (default = that legacy list, environment-overridable), not from a hard-coded constant.

### FR-007 — ReplyTo-gated result publish
- AC-014: Given an inbound message with `ReplyTo` set, when the notification reaches a terminal state, then the corresponding result event is sent to the queue named by `ReplyTo`.
- AC-015: Given an inbound message with no `ReplyTo` (the mi-reportdata MVP), when the notification reaches a terminal state, then no result event is published and the send completes silently.

### FR-008 — Result-event contract parity
- AC-016: Given a `markSent()` / `markFailed()` outcome, when the result event is built, then its name and payload match the legacy golden-master payload exactly (only routing changes from topic to `ReplyTo` queue).

### FR-009 — Query API returns all columns
- AC-017: Given a persisted notification, when the read API is queried by id, then the response contains all columns of the `notification` table.
- AC-018: Given the query API, when any request is made, then it is read-only (no command/write side effects).

### FR-010 — Integration test harness (local)
- AC-019: Given the integration test suite with WireMock-stubbed Gov.Notify/O365 (no external calls), when run locally, then a message on the command queue drives blob download → Gov.Notify stub → row SENT → result-queue message, and separate tests assert retry, redelivery/idempotency, atomicity, and blob 403/404.

### FR-011 — Validation pipeline (CI)
- AC-020: Given a pull request, when the GitHub Actions workflows run (`ci-draft.yml` → `ci-build-publish.yml` build+test, `code-analysis.yml` PMD lint, `secrets-scanner.yml`, `codeql.yml`), then the build, test, and lint gates execute (running the FR-010 unit + integration suites) and must all pass before merge.

### FR-012 — STE simulator / stub routing
- AC-021: Given the STE environment, when the service sends via Gov.Notify or O365, then it targets the configured sandbox/test-mode or mock endpoint (not production), and no real email is dispatched.

### FR-013 — Managed-identity RBAC for ASB
- AC-022: Given the service running in a non-emulator environment, when it connects to ASB, then it uses `DefaultAzureCredential` and no SAS token or connection string is present in config, env vars, or Helm values.
- AC-023: Given the managed identity, when RBAC is inspected, then it holds Azure Service Bus Data Receiver on the command queue (`ng-send-email`) + Data Sender on the result queue (`mi-reportdata-notification-result`) at queue (entity) scope (least-privilege, not namespace scope); the set of `ReplyTo` values must stay within the granted queue(s).

### FR-014 / FR-015 — Identity + ASB provisioning
- AC-024: Given the deployed identity, when RBAC is listed, then it holds ASB Data Receiver + Data Sender (queue (entity) scope), Storage Blob Data Reader on the attachment container(s) (including mi-reportdata's, cross-context), and Key Vault Secrets User — and no ASB SAS secret exists in Key Vault.
- AC-025: Given the ASB namespace, when provisioned, then the command and result queues exist and the identity's role assignments are present.

### FR-017 — Flux CD deployment
- AC-026: Given the STE environment, when the service is deployed, then it is via a Flux HelmRelease of the shared `springboot-app` chart with the per-context identity and CSI secret access, and no Helmsman/`cpp-aks-deploy` path is used.

### FR-018 — SenderFactory routing
- AC-027: Given a recipient domain ending in `cjsm.net` (or configured domain), when routing, then Office 365 (APIM) is selected.
- AC-028: Given an attachment > 2MB, when routing, then Office 365 (APIM) is selected.
- AC-029: Given a ≤2MB attachment and a non-cjsm domain, when routing, then Gov.UK Notify is selected.

### FR-019 — Office 365 immediate delivery
- AC-030: Given a successful O365 APIM response, when the `send-email` task completes, then `markSent()` is called directly and no `check-email-status` task is scheduled.

### FR-020 — Failure handling + DLQ
- AC-031: Given a transient send failure, when the task runs, then `markAttempted()` is recorded (no result event) and the task is re-scheduled per the retry list.
- AC-032: Given a permanent failure or exhausted retries, when the task runs, then `markFailed()` is called, the `notification-failed` result event is published only if `ReplyTo` is present, and the task returns COMPLETED (does not keep throwing).
- AC-033: Given an inbound consume failure before commit, when max redeliveries are exceeded, then the message lands on the DLQ.

### FR-022 — At-least-once safety
- AC-035: Given a task re-run against an already-terminal row, when it reaches `markSent()`/`markFailed()`, then the state-transition guard blocks the update and suppresses a duplicate result event.
- AC-036: Given a crash after the Gov.Notify send but before recording, when the task re-runs, then the service queries Gov.Notify by reference and does not re-send.

### FR-023 — Observability
- AC-037: Given the running service, when logs are emitted, then they are valid JSON to stdout with `correlationId` + `requestId` MDC fields.
- AC-038: Given DLQ activity, when messages accumulate, then DLQ depth is exposed as a monitored metric.

### FR-024 — Secured query API
- AC-039: Given an unauthenticated request to the query API, when received, then it is rejected; an authenticated request is served.

## Interface contracts (logical)

The observable contracts the service must honour. **Logical only** — names, fields, parity. The
**physical binding** (ASB envelope/serialisation, event-schema-owning repo, versioning) is delegated to
Architecture & Design (Stage 2); see the Handoff manifest.

### Inbound — `send-email-notification` command (ASB command queue)
Provenance = retained from legacy `notificationnotify.command.send-email-notification` vs added new.

| Field | Type | Required | Provenance | Notes |
|-------|------|----------|-----------|-------|
| `notificationId` | UUID | yes | legacy | dedupe / idempotency key |
| `templateId` | UUID | yes | legacy | Gov.UK Notify template |
| `sendToAddress` | string | yes | legacy | recipient |
| `fileUri` | string (URI) | no | **new** — replaces legacy `fileId` (UUID FileService id → Azure Blob URI) | attachment location |
| `replyToAddress` | string (email) | no | legacy | Gov.UK Notify **email** reply-to address |
| `replyToAddressId` | UUID | no | legacy | Gov.UK Notify reply-to-address id |
| `personalisation` | map<string,object> | no | legacy | Notify template params |
| `clientContext` | string | no | legacy | optional passthrough; persisted (`client_context`) and echoed into result events for parity (present-if-set); **not** a routing key |

Required set (`notificationId`, `templateId`, `sendToAddress`) and `additionalProperties: false` are legacy-faithful.

> **`materialUrl` is supported** (optional string, as in legacy): the gateway fetches the document through
> the Material service and attaches it. A non-blank `materialUrl` wins over `fileUri`; a blank one counts as
> absent. See ADR-002 D4 and D8–D11.

> **Transport property — not a body field:** the ASB `ReplyTo` message property (result-event routing,
> FR-007) is **not** part of the command body. It is captured at ingest into the `notification.result_queue`
> column and read back at the terminal hop to route the result event. It is distinct from the Gov.Notify
> **email** `replyToAddress`/`replyToAddressId` body fields above.

### Outbound events — result events (queue-routed to `ReplyTo` when present)
- `notification-sent` and `notification-failed` — full logical payloads **pulled from the legacy
  service** and captured in [`legacy-result-event-contract.md`](legacy-result-event-contract.md) (the
  golden-master). MVP scope = these two only. Parity mandate (NFR-001): names + payloads byte-for-byte;
  consumers are the originating contexts; `additionalProperties: false` — add no fields.

### External providers (logical)
- **Gov.UK Notify** — send email (templateId + personalisation + optional attachment) → returns a Notify
  reference id; poll delivery status → terminal `DELIVERED` / permanent failure.
- **Microsoft O365 (APIM)** — out of MVP scope (Milestone 6); treated as immediately DELIVERED.

### Query / REST API (logical)
- Read-only lookup + search returning **all columns** of the `notification` table (FR-009).

### Error behaviour (to preserve)
- **Permanent (fail fast, no infinite retry):** Blob 403/404 (FR-004).
- **Transient (retry per the preserved duration list `60,300,1800,3600,7200,14400`):** send/poll
  transient failures (FR-006 / FR-020).
- Concrete provider status-code → transient/permanent mapping is delegated to Stage 2.

## Constraints

- **Legislative / policy:** UK GDPR and Data Protection Act 2018 apply; notification content is
  OFFICIAL-SENSITIVE; data-retention periods must be defined and enforced; subject-access must be
  supportable.
- **GDS / HMCTS:** Coding in the open — the repo is public from day one and must be owned by an
  `hmcts` GitHub team (admin) before creation. Conventional Commits, PR with ≥1 human approval, no
  direct commits to `main`.
- **Azure posture:** managed-identity + Azure SDK is the only permitted integration path — no SAS
  tokens, connection strings, or account keys (emulator-only exception for local/test).
- **Tech stack:** Spring Boot 4.x / Java 25+ / Gradle, scaffolded from the HMCTS Spring Boot template;
  Postgres 16 + Flyway; JSON logging to stdout; deviations require an ADR. Because it consumes the ASB
  command message directly (no HTTP contract for the command path), the API-first "api-… repo precedes
  service" rule applies only to the query REST API — whether a matching `api-…` spec repo is required
  there is confirmed during that implementation.
- **Behavioural contract:** result-event names/payloads, sender routing, and retry/poll timings must
  not change (golden-master parity).
- **ASB routing model:** shared command queue **per command type** (not per client) inbound;
  per-originator result queues outbound; queue-scoped ASB RBAC (Data Receiver on the command queue +
  Data Sender on each provisioned result queue; the set of `ReplyTo` values must stay within the granted
  queues).

## Assumptions

- cp-task-manager (`uk.gov.hmcts.cp:task-manager-service`) is available as an embedded library and its
  published version's Spring Boot / Java baseline is compatible with Spring Boot 4.x / Java 25+
  (confirmed compatible).
- The mi-reportdata report bytes are reachable via an Azure Blob `fileUri` that this service reads
  with Storage Blob Data Reader on mi-reportdata's container. **Producer-side mechanism = Option A
  (decided):** mi-reportdata's `generate-nces-mi-extract` query handler uploads the report to blob and
  returns the `fileUri`. Producer-side work lives in a separate repo, but the interface is settled.
- The ASB command/result queues and namespace are provisioned by the Platform team out-of-band
  (matching the Flux/idam model).
- ACR image pull and App Insights/monitoring identity are on the AKS node/kubelet identity, not the
  workload identity, so the per-context identity switch does not affect them (to confirm — OQ-2).

## Out of scope

- **Producer-side changes in `cpp-context-mi-reportdata`** (feature toggle `useServiceBus`, CSV upload
  to blob, ASB publish, its own RBAC) — separate repo; referenced only as the triggering dependency.
- **Letters** (`send-letter` + accepted/received long-poll + invalid-resend), and the ex-aggregate
  columns those need (`resend_attempts_remaining`, `postage`).
- **Bounced-email detection and POCA inbound mailbox flows** (IMAP pollers, `bounced_email_notified`,
  `poca_email` table), and their `@Scheduled` triggers.
- **Other originator contexts** and the topic → per-consumer queue cutover, `SystemIdMapper` removal
  work in consuming contexts, and the `clientContext` routing fallback.
- Retiring the legacy service and the legacy `fileId`/FileService path (handled at cutover, later).

## Dependencies

- **Upstream:** `cpp-context-mi-reportdata` uploading the NCES CSV to its blob container and publishing
  the `send-email-notification` command (with `fileUri`, fresh-UUID `notificationId`, no `ReplyTo`).
- **Platform:** ASB namespace + command/result queue provisioning and the two ASB role assignments on
  the managed identity; cross-context Storage Blob Data Reader grant on mi-reportdata's container
  (ownership/approval to be agreed).
- **Libraries / services:** cp-task-manager library; Gov.UK Notify API; Microsoft Office 365 via Azure
  APIM; Azure Key Vault (CSI); Azure Blob Storage; Flux CD config repo (`cpp-flux-config`) and the
  shared `springboot-app` Helm chart.
- **House-style reference repos (patterns to port from):** `cpp-mbd-idam-integration`,
  `service-cp-crime-hearing-results-document-subscription`, `cp-court-list-publishing-service`,
  `cpp-context-reference-data` (UC1 BYO filestore), and the legacy `cpp-context-notification-notify`.

## Open questions

1. OQ-1 — **ASO capability for ASB CRDs `[NEEDS CLARIFICATION]`.** Confirm whether the cluster's ASO
   version serves `servicebus.azure.com` (`Namespace`/`Queue`) and `authorization.azure.com`
   (`RoleAssignment`) at a stable API version, and whether ASO may create Service Bus **namespaces**
   (vs only queues inside a Platform-provisioned namespace). Decides whether provisioning is done via
   `cpp-helm-chart` CRDs or a Platform request. Owner: Platform — Due: TBD.
2. OQ-2 — **ACR pull / App Insights identity.** Confirm these run on the AKS node/kubelet identity, not
   the workload identity, so the per-context identity switch does not break image pull or monitoring.
   Owner: Platform — Due: TBD.
3. OQ-3 — **`sadevcommonscsl` shared storage account.** The infra analysis flags this as a possible
   genuinely-shared account referenced by no service value — confirm it is not needed by this service
   (expected not). Owner: Platform — Due: TBD.

## Handoff to Architecture & Design

Inputs the design stage must read (explicit paths — Stage 2 reads only what is listed here):
- `docs/pipeline/requirements.md` (this file)
- `docs/pipeline/legacy-result-event-contract.md` (pulled legacy result-event golden-master — logical payloads for `notification-sent` / `notification-failed`)

Design decisions explicitly delegated to Stage 2 (physical binding + design-material unknowns):
- ASB message envelope / serialisation mapping for the inbound command and the outbound result events (subject / applicationProperties / body), plus correlation/metadata propagation. (Result-event *routing* — the `ReplyTo` message property → queue — is already decided per FR-007; only the envelope mapping is Stage 2's.)
- Result-event schema-owning repo/module and versioning/evolution rules.
- Concrete provider error status-code → transient/permanent taxonomy.
- Dual-write atomicity approach — **resolved in Stage 2**: ingest INSERT + task-enqueue is atomic in one local transaction (co-located datasource), so no reconciliation sweep or transactional outbox is needed (FR-021 removed). The only residual is the upstream cp-task-manager stale-lock gap (design OQ-4, `cp-task-manager-stale-lock-gap.md`).
