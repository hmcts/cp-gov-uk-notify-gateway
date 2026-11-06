# [NG-S11] Failure handling: transient retry, permanent failure, and DLQ

## User story
As a **support/operations engineer**,
I want **transient send/poll failures retried per the preserved legacy duration list, permanent
failures or exhausted retries marked failed without infinite retry loops, and inbound consume failures
that exceed max redeliveries routed to the DLQ**,
so that **failures are visible and bounded rather than silent, indefinitely retried, or lost**.

## Background
Standalone hardening story (Milestone 7 / original Epic 3), independently testable once the core send
path (NG-S02) exists. Distinct mechanism from NG-S12 (at-least-once/idempotency guards): this story is
about *how many times and for how long* a failure is retried before giving up, not about *preventing
duplicate effects* across retries.

## Acceptance criteria
- [ ] AC-031: Given a transient send failure, when the task runs, then `markAttempted()` is recorded
  (no result event) and the task is re-scheduled per the retry list.
- [ ] AC-032: Given a permanent failure or exhausted retries, when the task runs, then `markFailed()` is
  called, the `notification-failed` result event is published to `ReplyTo` (*"only if `ReplyTo` is present"*
  is superseded by NG-S15 / FR-025: `ReplyTo` is now required), and the
  task returns COMPLETED (does not keep throwing — cp-task-manager has no FAILED/DLQ state).
- [ ] AC-033: Given an inbound consume failure before commit, when max redeliveries are exceeded, then
  the message lands on the DLQ.

## NFR links
- NFR-001 (Behavioural parity): permanent vs. transient failure classification ported verbatim from
  legacy `FailureSelector.java` (HTTP 400/413 and Blob 403/404 permanent; HTTP 500,
  `statusCode==0`/SSL-handshake-style messages, and the legacy default `!isPermanentFailure` transient).
- NFR-010 (Atomicity): no orphaned `QUEUED` row without a task, even on failure paths.

## Out of scope for this story
- The idempotency/duplicate-prevention guard on re-runs — NG-S12 (FR-022); this story assumes a task
  re-run is safe to attempt and focuses only on retry-count/DLQ bounding.
- DLQ-depth monitoring/metrics — NG-S13 (FR-023); this story produces DLQ messages, it does not monitor
  them.

## Definition of done
- [ ] Code reviewed and approved.
- [ ] All ACs covered by automated tests (unit + integration), including the full permanent/transient
  error-taxonomy mapping ported from legacy `FailureSelector.java`.
- [ ] **FR-010/AC-019 harness scenario covered by this story**: the retry sub-scenario of AC-019
  (transient failure → re-scheduled per the retry list, observed via the Testcontainers/ASB-emulator/
  WireMock harness).
- [ ] No critical or high Snyk findings introduced.
- [ ] Deployed to and verified on sandbox.
- [ ] Jira ticket updated with test evidence (once a ticket exists — see Notes).

## Notes / open questions
- **FR/AC traceability:** FR-020 → AC-031, AC-032, AC-033. **FR-010 → AC-019** partially covered here
  (retry sub-scenario) — see `_index.md` for the full breakdown.
- **Reference-only Jira key:** FR-020 has **no** Jira key reserved yet in requirements.md's Jira column
  (blank). Vertical-slice ticket for NG-S11 will be created later, once the user approves ticket
  creation.
- Depends on NG-S02 (core send path) existing; independent of NG-S03/NG-S10/NG-S12.
