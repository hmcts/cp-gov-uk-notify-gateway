# ADR 004 — The Office 365 route reuses the legacy notifyatt Azure Function

**Status**: Accepted (2026-10-20)
**Date**: 2026-10-20 · **Deciders**: user ruling 2026-10-20 (option 1 of the O365 analysis)

## Context

- Gov.Notify rejects attachments over 2 MB. Legacy sends those emails through an Azure Function
  (`fa-<env>-ccp<stack>-notifyatt`, `Generate-Notification-Email`) that renders its own copy of the
  template and sends over Office 365 SMTP (`MicrosoftOffice365ClientService`, `HttpConnectionHelper`,
  `cpp-context-notification-notify@f4cb27d`).
- The gateway routed these emails to `Office365Client`, which threw, so they ended FAILED (413).
- Alternatives analysed: Microsoft Graph `sendMail` with the gateway's managed identity (needs a tenant
  `Mail.Send` grant and upload-session code), direct SMTP, or a new gateway-owned function
  (`analysis/notification-gateway/legacy-o365-email-and-stubbing.md` § 9).

## Decision

We will call the legacy function exactly as legacy does.
- `POST` the endpoint with `content-type: application/json` and `Ocp-Apim-Subscription-Key`, body
  `{emailAddress, templateId, reference, personalisation, attachment{filename, content}, replyToAddressId?}`.
- A `200` means the function has sent the email: the notification is marked SENT at once, with no
  status poll (legacy marks it `DELIVERED`). Subject and body for `notification-sent` come from the
  response (`subject`; the text of line 2 of `templateContent`), and the reply-to address is the
  command's, or `NOREPLY@noreply.com`.
- Failures follow legacy `FailureSelector`: `400` and `413` are permanent, everything else is retried on
  `email-durations-secs`. No proxy, as legacy; unlike legacy, connect (30s) and request (300s) timeouts
  bound the call (ADR-002 D13).
- Endpoint and key arrive from Key Vault (`CP-NG-OFFICE365-ENDPOINT`, `CP-NG-OFFICE365-SUBSCRIPTION-KEY`),
  per-environment copies of Vault `secret/<env>/<stack>/notify_email_fa_endpoint` and
  `.../hearing_slots_subscription_key` (DEV pinned to `devccm01`, MDV/MDT `secret/mdv/validation`). The
  endpoint is a credential: its `code` query parameter is the function key (`clientId` names it). The app
  refuses to start without either value.
- Deviations from legacy are recorded in ADR-002: D13 (the send) and D14 (routing on attachment size only;
  the recipient-domain route is not implemented).

## Options considered

| Option | Pros | Cons |
|--------|------|------|
| **A — reuse the legacy function (chosen)** | Parity; nothing new to provision; no tenant ask; endpoint already reachable from AKS (verified in STE) | Static shared key (the hearing-slots APIM key); depends on legacy infra; template copies live in the function's storage |
| B — Graph `sendMail` + managed identity | No stored secret; enables legacy decommission | `Mail.Send` grant + application access policy; upload-session flow for > 3 MB |
| C — direct SMTP from the gateway | Reuses legacy SMTP code | SMTP 587 egress; SMTP basic auth is being retired |
| D — new gateway-owned function | Isolates `Mail.Send` | Another deployable to own |

## Consequences

- No new infrastructure. The new per-environment step is the two Key Vault copies, before or with the
  image.
- Retiring legacy must keep the notifyatt Function App, its templates and the key while the gateway uses
  them. Moving to option B is the follow-up that removes the dependency.
- In STE the function has `DISABLE_SEND_EMAIL=true`: it answers `200` without sending, so the gateway
  records SENT, as legacy does.
- Rotating the hearing-slots key or the function key needs the Key Vault copies updated too.

## Compliance notes

- No secret values here; references are by Key Vault name, Vault path and env-var name only.
