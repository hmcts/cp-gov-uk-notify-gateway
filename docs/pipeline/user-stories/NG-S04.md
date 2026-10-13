# [NG-S04] Read-only query API for notification status (open interim — auth deferred to NG-S14)

> **Superseded by ADR-002 D7 (query API removed).** The notification query API this story covers was removed
> by [ADR-002 D7](../adrs/002-deviations-from-legacy-notify-context.md); the story is kept for history only.

## User story
As a **support/operations engineer (or an authenticated machine consumer once NG-S14 lands)**,
I want **to query a notification's full record via a read-only REST API**,
so that **I can triage incidents and check delivery status without direct database access**.

## Background
Independently valuable and independently testable (can be built/tested against seeded rows without the
full send pipeline being complete), and depends only on the NG-S01 schema. This story was re-sliced from
an earlier version that bundled FR-009 (this read API) together with FR-024 (its IDAM/Entra auth) into
one story. Authentication/authorisation is a separable concern — the auth *pattern* itself is a genuine
open architectural decision (Pattern 1 vs Pattern 2 — see NG-S14) — and is now delivered as its own
story, **NG-S14**.

**Security posture (interim — decided by coordinator).** The endpoint ships **open (permit-all,
unauthenticated)** until NG-S14 delivers authentication. Rationale: a deny-all endpoint cannot be
exercised or verified on sandbox and delivers no value before auth lands, so a locked-down interim was
rejected in favour of an open one. **Risk accepted:** because responses carry OFFICIAL-SENSITIVE PII
(`send_to_address`), the deployed endpoint is an unauthenticated PII-returning endpoint for the interim
window — this consciously reverses the earlier "no story ships an unauthenticated PII endpoint" rule for
NG-S04. **Mitigation (mandatory):** NG-S04 must remain reachable only within the trusted cluster/network
boundary (internal ingress, not a public/unauthenticated route), and **NG-S14 must land before any
external exposure**. See the query-API implementation and authentication notes (retired — see ADR-002 D7).

## Acceptance criteria
- [ ] AC-017: Given a persisted notification, when the read API is queried by id
  (`GET /notifications/{notificationId}`), then the response contains all columns of the `notification`
  table.
- [ ] AC-018: Given the query API, when any request is made, then it is read-only (no
  command/write side effects) — includes the paged search endpoint
  (`GET /notifications?status=&createdFrom=&createdTo=&page=&size=`).

## NFR links
- NFR-004 (Security — logging/PII): responses may contain PII (`send_to_address`); the API itself must
  never log PII, secrets, tokens, or full request/response bodies.
- NFR-005 (Data classification): notification data is OFFICIAL-SENSITIVE. **Interim exposure risk
  accepted** — the endpoint is unauthenticated until NG-S14; safe only behind the trusted network
  boundary until then (see Background). NG-S14 closes this.
- NFR-006 (OWASP): assessed against the OWASP Top 10 for the endpoint's own logic (e.g. injection,
  data exposure via error messages) — no unresolved Critical/High. Auth-specific OWASP concerns
  (broken authentication/access control) are owned by NG-S14.

## Out of scope for this story
- Authentication/authorisation of this API — see NG-S14. This story ships the endpoint **open** as an
  interim (see Background); NG-S14 introduces the deny/auth behaviour.
- Write/command endpoints (the "no REST" rule applies only to the command path, not this API).
- `sendToAddress`-based search filtering — optional per the Q5 default; may be added as a follow-on if
  a real consumer needs it.
- The companion `api-notification-gateway` OpenAPI repo's CI/publishing setup (tracked as a Stage 2
  follow-up, not an FR/AC in this backlog).

## Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs covered by automated tests (unit + integration), plus the positive happy-path acceptance
  (BDD) scenario for the query journey.
- [ ] The interim open posture is intentional and visible in code — a `permitAll()` security
  configuration marked as interim-until-NG-S14 (not a silent absence of security). No test asserts
  denial for this story (the deny/auth test is NG-S14's).
- [ ] Contract authored as an OpenAPI spec in `contracts/openapi/gov-uk-notify-gateway.openapi.yaml`
  (no code generation); the controller's conformance to it is enforced by a spec-validation test
  (`swagger-request-validator`), so response/param/status drift fails the build.
- [ ] Runtime input validation on the query/path params via Jakarta Bean Validation
  (`spring-boot-starter-validation`, `@Validated`) — **interim**, until runtime OpenAPI request
  validation replaces it; constraints mirror the spec's parameter schemas (`page >= 0`,
  `1 <= size <= 200`, ISO-8601 dates, allowed `status`).
- [ ] Accessibility audit — not applicable (machine/API consumer, no UI, NFR-015).
- [ ] No critical or high Snyk findings introduced.
- [ ] OWASP Top 10 review passed for the endpoint's own logic (NFR-006); the auth-specific OWASP
  review is NG-S14's DoD.
- [ ] Deployed to and verified on sandbox (now feasible because the endpoint is open) — kept behind the
  trusted network boundary; not exposed on any public/unauthenticated route until NG-S14.
- [ ] Jira ticket updated with test evidence.

## Notes / open questions
- **FR/AC traceability:** FR-009 → AC-017, AC-018 only. FR-024 → AC-039 has been sliced out into
  NG-S14 (see `_index.md`'s FR→Story mapping) — it no longer belongs to this story.
- **Implementation approach:** hand-written controller + local `NotificationView` DTO (Option B) — no
  companion `api-notification-gateway` spec / generated interface for now; contract-first migration is
  tracked as follow-up debt in the query-API implementation note (retired — see ADR-002 D7).
- **Jira story ticket:** [PEG-3383](https://tools.hmcts.net/jira/browse/PEG-3383).
- Depends on NG-S01 (schema) only; does not require NG-S02/NG-S03 to be complete (can be tested with
  seeded rows), though it has no real data to show until NG-S02 lands.
- **Security sequencing:** open interim; NG-S14 (authentication/authorisation) MUST land before the API
  is exposed on any untrusted/public route.
