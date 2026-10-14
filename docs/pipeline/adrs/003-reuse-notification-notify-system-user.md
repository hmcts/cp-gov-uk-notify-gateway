# ADR 003 — The gateway calls Material as the notification-notify system user

> Repo ADR-003 = KB ADR-001 (`cp-meta-arch` change `2026-10-notify-gateway-material-url`,
> `adrs/001-reuse-notification-notify-system-user.md`). Renumbered because this repo
> already has ADR-001/002. References inside this ADR to `current-state.md`, `platform/security.md`,
> `gap:` ids and the delivery plan are to that KB change, not to this repo.

**Status**: Accepted (gate 2, 2026-10-09)
**Date**: 2026-10-08 · **Deciders**: user ruling 2026-10-08; gate-2 reviewer

## Context

- To fetch a Material document, the gateway must send `CJSCPPUID` = a Users & Groups user in
  "System Users". Material's rule "Query - material - System user" admits that group
  (`current-state.md` § System users; `platform/security.md` § Service-to-service trust).
- Legacy makes this exact call as its own context system user (`DownloadRestClient` →
  `ServiceContextSystemUserProvider`, `cpp-context-notification-notify@f4cb27d`).
- That account (`SystemUsers.NOTIFICATION_NOTIFY`, `cpp-platform-priming@906961e`) is primed in
  non-live stacks. It is observed in "System Users" in STE, and its JNDI value equals the priming
  UUID in STE stacks 02/03.
- Creating a new account in live environments has no located route (`gap:319`).
- The gateway is the email successor of notification-notify, not a new business actor.

## Decision

We will send the notification-notify system user's UUID as `CJSCPPUID` on the gateway's Material
query.
- The gateway reads it from env var `CP_NG_SYSTEM_USER_ID`, mapped by Flux `secretProvider` from
  Key Vault secret `NOTIFICATIONNOTIFY-SYSTEM-USER-ID`. That secret is a per-environment copy of
  Vault `secret/<env>/<stack>/system_users_notificationnotify_password`.
- The app refuses to start if the value is missing or not a UUID, so the secret reaches each
  environment before or with the image.

## Options considered

| Option | Pros | Cons | Evidence |
|--------|------|------|----------|
| **A — reuse notification-notify's user (chosen)** | Account and membership already exist everywhere legacy runs; no priming, Users & Groups or Material change; identical to legacy (parity) | Material's audit cannot tell gateway from legacy calls; two stores hold the value; retiring legacy must keep the account | `cpp-context-notification-notify@f4cb27d` `DownloadRestClient`; `cpp-platform-priming@906961e` `SystemUsers` |
| B — dedicated gateway user | Clean attribution; independent lifecycle | Priming change plus live account creation with no known route (`gap:319`); blocks live rollout | `gap:319` |
| C — no user (call Material anonymously) | Nothing to provision | Material rejects it (500/403 without `CJSCPPUID`, observed in STE) | `current-state.md` § How legacy reaches Material |

## Consequences

- No account work in any environment. The one new per-environment step is the Key Vault copy:
  delivery-plan slices 6 and 8, owned by Platform.
- Material's audit attributes gateway calls to notification-notify. The gateway's per-download
  log line (notification id, stage, status) is the attribution record (NFR-005).
- Decommissioning dependency (NFR-008): retiring legacy notification-notify must keep the
  Users & Groups account and the Key Vault copy while the gateway uses them. Routed to the parent
  CR's legacy-retirement scope, and to the legacy component sheet at fold-back
  (`delivery-plan.md` § KB close-the-loop).
- Drift between the two copies shows as 403 retries. Mitigated by the STE equality check and the
  403 alert (NFR-006).
- Platform must confirm the Key Vault copy approach (OQ-8). That is a dependency on the Flux work
  order, not a design blocker.

## Compliance notes

- Trigger: security decision with no clear MbD precedent (sharing a system identity across two
  services).
- No live secret values here; references are by Key Vault, Vault path and env-var name only.
