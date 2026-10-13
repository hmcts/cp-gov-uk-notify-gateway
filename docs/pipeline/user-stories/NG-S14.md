# [NG-S14] Authentication and authorisation of the notification query API

> **Superseded by ADR-002 D7 (query API removed).** The notification query API this story covers was removed
> by [ADR-002 D7](../adrs/002-deviations-from-legacy-notify-context.md); the story is kept for history only.

## User story
As a **support/operations engineer (or an authenticated machine consumer)**,
I want **the notification query API (delivered deny-all-by-default in NG-S04) to authenticate and
authorise every request**,
so that **PII is never exposed to an unauthenticated caller, and only authorised callers are served**.

## Background
Sliced out of NG-S04, which originally bundled FR-009 (the read API itself) and FR-024 (its IDAM/Entra
auth) into one story per an earlier Q3 carry-forward. FR-024 carries separable value — the auth
*pattern* is a genuine open architectural decision, not yet resolved — so it is now delivered as its own
story. **Depends on NG-S04**: the endpoint must exist (deployed deny-all-by-default) before it can be
secured with a real auth pattern.

The choice of auth pattern is **not yet decided**. Two candidates are analysed in
the query-API authentication note (retired — see ADR-002 D7):
- **Pattern 1** — `cp-auth-rules-filter` header-trust (`CJSCPPUID` injected by the fronting IDAM
  gateway, U&G permissions + Drools rules); security rests on the network boundary, no in-service
  cryptographic token validation.
- **Pattern 2** — Spring Security OAuth2 resource server against Entra ID; genuine cryptographic JWT
  validation via JWKS, `roles` claim mapped to `ROLE_*`, `denyAll()` default (the sanctioned
  `cpp-mbd-notification-svc-pilot` "Modern by Default" blueprint).

There is **no platform-sanctioned winner** — an ADR (skill: `skills/adr-template.md`) recording the
chosen pattern **MUST be raised and agreed before implementation begins**.

## Acceptance criteria
- [ ] AC-039: Given an unauthenticated request to the query API, when received, then it is rejected
  (401/403); an authenticated request (valid IDAM/Entra caller, per the chosen auth pattern) is served.

## NFR links
- NFR-004 (Security — logging/PII): the auth layer itself must never log PII, secrets, tokens, or full
  request/response bodies (e.g. no bearer tokens or `CJSCPPUID` values in access logs).
- NFR-005 (Data classification): notification data is OFFICIAL-SENSITIVE; this story is what makes the
  endpoint safe to expose to real callers (NG-S04 delivers only the deny-all default).
- NFR-006 (OWASP): assessed against the OWASP Top 10 — no unresolved Critical/High, in particular A01
  (broken access control) and A07 (identification/authentication failures).

## Out of scope for this story
- The read API endpoint itself (its query logic, response shape, pagination) — NG-S04.
- Any write/command endpoints (no REST command path exists in this backlog).
- The companion `api-notification-gateway` OpenAPI repo's CI/publishing setup (tracked as a Stage 2
  follow-up, not an FR/AC in this backlog).

## Definition of done
- [ ] Code reviewed and approved.
- [ ] AC-039 covered by automated tests (unit + integration), including an explicit
  unauthenticated-request-rejected test and a valid-caller-served test.
- [ ] OWASP Top 10 review passed (NFR-006).
- [ ] No critical or high Snyk findings introduced.
- [ ] ADR recorded for the chosen auth pattern (Pattern 1 vs Pattern 2) before implementation, per
  `skills/adr-template.md`.
- [ ] Deployed to and verified on sandbox, with NG-S04's endpoint switched from deny-all to enforcing
  the chosen auth pattern.
- [ ] Jira ticket updated with test evidence (once a ticket exists — see Notes).

## Notes / open questions
- **FR/AC traceability:** FR-024 → AC-039 (sliced out of NG-S04, where it was originally bundled with
  FR-009 per the earlier Q3 carry-forward).
- **Auth pattern decision is open** — see the query-API authentication note §7 (retired — see ADR-002 D7), which
  gates the ADR on:
  1. **Exposure** — is the read API behind the fronting IDAM gateway (like court-list), or a standalone
     ingress reachable by machine consumers? Decides Pattern 1 vs 2 more than anything else.
  2. **Day-one consumer** — is the first real caller a human in a support tool, or a machine/monitoring
     job?
  3. **Boot 4 compatibility** (Pattern 1 only) — confirm `cp-auth-rules-filter:1.0.7` /
     `cp-audit-filter-springboot:1.0.5` work on Spring Boot 4.1 before wiring.
  4. **Invalid-token status** (Pattern 1's `JWTFilter`) — returns 400 for an invalid token; AC-039
     wants 401/403. Not applicable to Pattern 2 (resource server returns 401 natively).
- Depends on NG-S04 (the endpoint must exist, deployed deny-all-by-default) — not on NG-S02/NG-S03.
- **Jira story ticket:** [PEG-3410](https://tools.hmcts.net/jira/browse/PEG-3410) (created under epic
  [PEG-3350](https://tools.hmcts.net/jira/browse/PEG-3350), labels `claude-generated`/`needs-review`;
  relates to PEG-3383).
