# Error handling

How NG treats a failed call to each dependency: what is retried, what fails the notification at once, and what
the requester gets back. Alerts raised by these outcomes are in [ALERTS.md](ALERTS.md).

## Outcomes

| Outcome | What happens |
|---|---|
| **Retried** | The task is rescheduled on its retry schedule (below). A later success carries on as normal. If the last attempt still fails, the notification is marked `FAILED` and `notification-failed` is published to `ReplyTo` |
| **Permanent** | No retry. The notification is marked `FAILED` at once and `notification-failed` is published to `ReplyTo` |
| **Sent** | The notification is marked `SENT` and `notification-sent` is published to `ReplyTo` |

Every `FAILED` logs a `notificationOutcome=failed` line with an `outcomeReason` (shown in brackets below), which
fires the requester-outcomes alert.

### Retry schedules

Defaults from `application.yaml`, overridable per environment. One schedule covers every call a task makes, so a
send task's attachment download and its send share the same retries.

| Task | Env var | Intervals | Roughly |
|---|---|---|---|
| Send email, check email status | `CP_NG_EMAIL_RETRY_DURATIONS_SECS` | 1m, 5m, 30m, 1h, 2h, 4h | 7.5 hours |
| Send letter | `CP_NG_LETTER_RETRY_DURATIONS_SECS` | 1m, 5m, 30m, 1h, 2h, 4h, 4h | 11.5 hours |
| Check letter status until accepted | `CP_NG_LETTER_STATUS_RETRY_DURATIONS_SECS` | 1m, 5m, 30m, 1h, 2h, 4h | 7.5 hours |
| Check accepted letter until received | `CP_NG_LETTER_ACCEPTED_STATUS_RETRY_DURATIONS_SECS` | 12h × 16 | 8 days |

## Does "no HTTP status code" mean permanent?

**Only for Gov.Notify.** It depends on the dependency:

| Dependency | No HTTP response (DNS failure, connection refused, reset, timeout) |
|---|---|
| Gov.Notify | **Permanent**, unless the TLS handshake failed (then retried). Gov.Notify may already have accepted the notification and does not de-duplicate, so a retry could send it twice |
| Office 365 | Retried |
| Material (API and SAS download) | Retried |
| Blob storage | Not handled by NG (see [Blob storage](#blob-storage-fileuri-attachments)) |

Gov.Notify calls also have **no timeout** (notifications-java-client sets none on its `HttpURLConnection`). A
Gov.Notify that accepts the connection and never answers holds the task thread indefinitely: there is no failure,
no retry and no alert.

## Gov.Notify: send email and send letter

| Error | Handling | Outcome reason |
|---|---|---|
| HTTP 400, 404, 413 | Permanent | `provider-rejected` |
| Any other HTTP status (401, 403, 429, 5xx…) | Retried | `provider-failed` once retries run out |
| No response: DNS failure, connection refused, I/O error | Permanent | `provider-unreachable` |
| No response: timeout | Permanent | `provider-no-response` |
| No response: TLS handshake failure | Retried | `provider-unreachable` once retries run out |

A send that succeeds starts status polling (below). The no-response cases also count towards the provider-outage
alert.

## Gov.Notify: status checks (email and letter)

A failed status call is classified like a send: 400/404/413 and no response are permanent, everything else is
retried. The status Gov.Notify returns is handled as follows.

Rows follow the order Gov.Notify moves through the statuses, with the failure statuses last.

| Status | Email | Letter |
|---|---|---|
| `created`, `sending`, `pending-virus-check` | Polled again; `FAILED` (`status-unknown`) when polling runs out | Polled again; `FAILED` (`status-unknown`) when polling runs out |
| `accepted` | Polled again; `FAILED` (`status-unknown`) when polling runs out | Moves on to polling the accepted letter (every 12h, up to 8 days); `FAILED` (`status-unknown`) if it is still not `received` by then |
| `received` | Polled again; `FAILED` (`status-unknown`) when polling runs out | **Sent** |
| `delivered` | **Sent** | Not a letter status; if returned, `FAILED` (`delivery-failed`) |
| `validation-failed` | `FAILED` (`delivery-failed`) | Sent again from a fresh download, up to 5 times; then `FAILED` with 400 (`delivery-failed`) |
| `virus-scan-failed` | `FAILED` (`delivery-failed`) | `FAILED` (`delivery-failed`) |
| `temporary-failure`, `permanent-failure`, `technical-failure` | `FAILED` (`delivery-failed`) | `FAILED` (`delivery-failed`) |
| `not found`, anything unrecognised | `FAILED` (`delivery-failed`) | `FAILED` (`delivery-failed`) |

Gov.Notify's `temporary-failure` is a final status (for example, the inbox was full), so it is not retried.

## Office 365 (emails with attachments over 2MB)

The Office 365 route has a 30s connect timeout and a 300s request timeout.

| Error | Handling | Outcome reason |
|---|---|---|
| HTTP 400, 413 | Permanent | `provider-rejected` |
| Any other non-200 status | Retried | `provider-failed` once retries run out |
| Timeout | Retried | `provider-no-response` once retries run out |
| Connection or I/O failure | Retried | `provider-unreachable` once retries run out |
| Request could not be built (no call made) | Permanent | `provider-rejected` |
| 200 with a body NG cannot read | **Sent** (subject and body not recorded) | — |

An Office 365 send is recorded as sent at once; there is no status polling. Each retried timeout or I/O failure
counts towards the provider-outage alert.

## Material (`materialUrl` attachments and letter PDFs)

NG asks the Material API for a SAS link, then downloads the document from it. The Material API has a 5s connect
timeout and a 35s read timeout; the SAS download has a 30s read timeout plus an overall deadline. A retry asks
Material for a fresh link.

| Step | Error | Handling | Outcome reason |
|---|---|---|---|
| Before any call | `materialUrl` / `letterUrl` not on an allowed host, or SAS link fails validation | Permanent | `attachment-bad-link` |
| Material API | HTTP 400 | Permanent | `attachment-bad-link` |
| | HTTP 413 | Permanent | `attachment-too-large` |
| | HTTP 401, 403 | Permanent | `attachment-access-refused` |
| | HTTP 404 | Permanent | `attachment-not-found` |
| | Any other non-200 status | Retried | `attachment-unavailable` once retries run out |
| | 200 without a `Location` or `Content-Location` header | Retried | `attachment-unavailable` once retries run out |
| | Timeout, connection failure | Retried | `attachment-unavailable` once retries run out |
| SAS download | HTTP 401, 403 | Permanent | `attachment-access-refused` |
| | HTTP 404 | Permanent | `attachment-not-found` |
| | Document larger than the limit | Permanent | `attachment-too-large` |
| | Any other non-200 status | Retried | `attachment-unavailable` once retries run out |
| | Timeout, connection failure | Retried | `attachment-unavailable` once retries run out |

## Blob storage (`fileUri` attachments)

NG uses the Azure SDK's default retry policy for each call before its own handling applies.

| Error | Handling | Outcome reason |
|---|---|---|
| `fileUri` not on an allowed host | Permanent | `attachment-bad-link` |
| HTTP 404 | Permanent | `attachment-not-found` |
| HTTP 403 | Permanent | `attachment-access-refused` |
| Blob larger than `cp.notification.blob.max-attachment-bytes` (15MB) | Permanent | `attachment-too-large` |
| Any other HTTP status (401, 429, 5xx…) | Retried | `attachment-unavailable` once retries run out |
| No HTTP response (DNS, connection, I/O) once the SDK gives up | **Not handled** (see below) | none |

**Gap:** NG only catches the SDK's `BlobStorageException`, which carries an HTTP status. A network failure surfaces
as a different exception, so it reaches cp-task-manager unhandled. cp-task-manager retries the task while it has
attempts left, but NG's retry policy never sees the failure. When the attempts run out, cp-task-manager stops
picking the job up, so the notification stays `QUEUED`: no `notification-failed` is published and no alert fires.

## Any other unexpected exception

Anything a task does not catch, such as a database error, follows the same path as the blob gap above: retried by
cp-task-manager, then left `QUEUED` with no outcome event and no alert.
