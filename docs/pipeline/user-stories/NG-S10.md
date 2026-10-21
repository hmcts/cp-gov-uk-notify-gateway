# [NG-S10] Office 365 routing and immediate-delivery send path

## User story
As a **producer sending an email with an attachment larger than 2MB**,
I want **my email sent via Microsoft Office 365 (through the legacy notifyatt Azure Function) and marked
sent immediately on a successful response**,
so that **large attachments are not blocked by Gov.UK Notify's 2MB attachment limit**.

## Background
FR-018 (routing rule) and FR-019 (O365 send behaviour) are bundled: a routing decision that selects a
sender with no working implementation behind it delivers no value, and both requirements sit under the
single "Milestone 6 — Office 365 send path" heading in requirements.md. The `EmailClientFactory` seam was
scaffolded in NG-S02 with a throwing stub; this story replaces the stub with a client for the legacy
notifyatt Azure Function (ADR-004: reuse the legacy function, same request, headers and subscription key).
**Dependency/risk carried from requirements.md:** the mi-reportdata NCES CSV may exceed 2MB — until
this story lands, only ≤2MB Gov.Notify sends work; confirmed by Stage 2 not to be a go-live blocker
(mi-reportdata does not consume until the MVP is fully implemented).

## Acceptance criteria
- [x] AC-028: Given an attachment > 2MB, when routing, then Office 365 is selected.
- [x] AC-029: Given an attachment ≤ 2MB (or none), when routing, then Gov.UK Notify is selected.
- [x] AC-030: Given a successful (`200`) Office 365 response, when the `send-email` task completes, then
  the notification is marked SENT directly and no `check-email-status` task is scheduled.
- [x] AC-040: Given an Office 365 failure, when the `send-email` task handles it, then `400` and `413` (and
  a request that cannot be built) fail the notification at once, and every other status, I/O failure or
  timeout (connect 30s, request 300s) is retried on `email-durations-secs` and then fails (legacy
  `FailureSelector` parity; the timeouts are ADR-002 D13).
- [x] AC-041: Given a `200` whose body cannot be read, then the notification is still recorded SENT; given a
  failure while recording SENT, then recording is retried by its own `RECORD_SENT` task and, once retries run
  out, its job is left parked for manual replay — the email is never sent again.

## NFR links
- NFR-001 (Behavioural parity): size threshold and Office 365 call unchanged from legacy; deviations are
  ADR-002 D13 (send) and D14 (routing).

## Out of scope for this story
- **AC-027 (recipient-domain route, `cjsm.net`) — descoped.** Every environment sets the domain list to
  blank, so legacy never takes this route; it is not implemented (ADR-002 D14).
- Replacing the legacy function with Microsoft Graph `sendMail` and a managed identity (ADR-004 follow-up).
- Any change to the Gov.UK Notify send/poll path (NG-S02) — routing only selects between the two, it
  does not alter either sender's internal behaviour.

## Definition of done
- [ ] Code reviewed and approved.
- [x] All ACs covered by automated tests (unit + integration).
- [x] **FR-010/AC-019 harness scenario covered by this story**: the WireMock-stubbed Office 365 endpoint
  drives a routed send (Material document over 2MB) through this story's sender in the
  Testcontainers/ASB-emulator harness.
- [ ] No critical or high Snyk findings introduced.
- [ ] Key Vault secrets `CP-NG-OFFICE365-ENDPOINT` and `CP-NG-OFFICE365-SUBSCRIPTION-KEY` created before the
  Flux change and the image (the app refuses to start without them).
- [ ] Deployed to and verified on STE.
- [ ] Jira ticket updated with test evidence.

## Notes / open questions
- **FR/AC traceability:** FR-018 → AC-028, AC-029 (AC-027 descoped); FR-019 → AC-030, AC-040, AC-041 (new in this story; AC-031/AC-032 belong to NG-S11).
  **FR-010 → AC-019** partially covered here (Office 365 stub sub-scenario) — see `_index.md` for the full
  breakdown.
- **Jira:** [PEG-3386](https://tools.hmcts.net/jira/browse/PEG-3386).
- In STE the function has `DISABLE_SEND_EMAIL=true`: it answers `200` without sending, so STE verifies
  the call and the SENT outcome, not delivery.
