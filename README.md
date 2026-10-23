# cp-gov-uk-notify-gateway

Spring Boot (Modern by Default) service that dispatches outbound notifications — email and letter — on
behalf of CPP contexts. It is the re-platform of the legacy WildFly `cpp-context-notification-notify`.

## What it does

- **Consumes** `send-*-notification` commands from Azure Service Bus, one queue per command type
  (`ng-send-email`, `ng-send-letter`; no REST command API).
- **Sends email** via GOV.UK Notify, routing to **Office 365** for large (>2MB) or `cjsm.net`
  attachments; sends **letters** via GOV.UK Notify's precompiled-letter API: the PDF is fetched from
  Material by `letterUrl`, sent first class when the postage is `first`, and polled until Notify has
  received it; a letter that fails Notify's validation is sent again up to 5 times (ADR-002 D15/D16).
- **Fetches attachments** from Azure Blob Storage by `fileUri` (BYO filestore), or from the Material
  service by `materialUrl` (credential-less download of the SAS link Material returns; ADR-002 D8/D9).
- **Persists** each notification (Postgres/Flyway) and runs send/retry/status-polling on
  **cp-task-manager**.
- **Publishes the outcome** (sent / failed / bounced) to the queue named in the inbound message's
  `ReplyTo` property — per-originator, and only when `ReplyTo` is present (otherwise fire-and-forget).
- **Serves no business HTTP API** — only the actuator health, info and Prometheus endpoints are
  reachable over HTTP; every other path is refused (ADR-002 D7).

## Tech

Java 25+ · Spring Boot 4.x · Azure Service Bus & Blob via **managed-identity RBAC** (no SAS) ·
cp-task-manager · Flyway/Postgres · deployed to AKS via Flux GitOps.

## Design & plan

Design, implementation plan and epics live in the knowledge base:
`platform-engineering-knowledge-base/features/notification-notify-mbd-rewrite/`.
