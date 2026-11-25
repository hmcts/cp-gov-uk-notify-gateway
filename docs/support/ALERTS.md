# Alerts

All of NG's alerts and the action groups they notify (who is emailed or sent an SMS) are configured in
[cp-gov-uk-notify-gateway-terraform-alerts](https://github.com/hmcts/cp-gov-uk-notify-gateway-terraform-alerts), and
provisioned in `RG-<ENV>-NG-ALERTS-01`. Change thresholds, recipients or queues there.

## Log alerts

Azure Monitor log search alerts on NG's logs. Both rules query `ContainerLogV2` for the `springboot-app` container of
`gov-uk-notify-gateway*` pods. They run every 5 minutes over the last 15 minutes, are stateful (one email per
alert until it resolves) and resolve themselves once the condition clears. In the portal, fired alerts are
listed under the target Log Analytics workspace, not under the alerts resource group.

The fields below are top-level JSON fields on NG's log lines (see `logging/LogField.java`). Renaming one breaks
the alert that reads it.

| Alert → action group emailed | Threshold | Log expression it counts | Message logged | Scenario that logs it |
|---|---|---|---|---|
| **requester-outcomes** → Notify Gateway Requesting Teams | ≥1 line in 15 min, counted separately for each namespace, `replyQueue`, outcome, reason and channel | `notificationOutcome` = `rejected` | WARN `Rejected the <channel> request for reply queue '<q>': <reason>` | The command can't be parsed (`outcomeReason` = `unreadable-request`) or fails payload validation, e.g. a missing templateId (`invalid-request`). This line is logged only after the `notification-validation-failed` event is published |
| | | `notificationOutcome` = `failed` | WARN `Notification '<id>' for reply queue '<q>' marked FAILED (statusCode '<n>'): <reason>` | `provider-rejected`: Gov.Notify answered 400/404/413<br>`provider-unreachable` / `provider-no-response`: the send got no HTTP response (DNS failure or connection refused / timeout)<br>`provider-failed`: a retried status (429, 5xx…) still failing when retries run out<br>`attachment-not-found` / `-access-refused` / `-too-large` / `-bad-link`: attachment download answered 404 / 401 or 403 / 413 / anything else<br>`attachment-unavailable`: attachment download still failing when retries run out<br>`delivery-failed`: Gov.Notify's status check reports that the email or letter could not be delivered<br>`status-unknown`: Gov.Notify still hasn't finished when status polling runs out |
| **provider-outage** → Notify Gateway Support | ≥5 lines in 15 min, counted separately for each namespace, `dependency`, `channel` and `reason` | `operation` = `send`, `dependency` in (`gov-uk-notify`, `microsoft-365`), `outcome` in (`transient-failure`, `permanent-failure`), `reason` in (`unreachable`, `no-response`) | WARN `Permanent send failure for notification '<id>' (http 'none') — marking FAILED` (email)<br>WARN `Permanent letter send failure for notification '<id>' (http 'none') — marking FAILED` (letter) | A Gov.Notify send that gets no HTTP response: DNS failure or connection refused (`unreachable`), or a timeout (`no-response`). The notification also fails, so each one also logs a requester-outcomes `failed` line |
| | | | INFO/WARN `Transient failure '<call>' for notificationId '<id>' (the service could not be reached \| did not respond) — will retry \| scheduling the final attempt \| did not recover after exhausting retries — marking FAILED` | A send that is retried and gets no HTTP response: an Office 365 I/O failure, or a Gov.Notify SSL handshake failure. Every attempt logs a line, so one notification can count several times |
| *(none)* | — | — | INFO `Sent notification '<id>' via '<dependency>' …` / `Sent letter …` | Successful send |

**The threshold applies to each combination of split dimensions separately.** For example, 3 `unreachable` lines plus
2 `no-response` lines, or 3 email plus 2 letter failures, do not fire provider-outage.

**HTTP error responses are not an outage.** When the provider (or a proxy in front of it) answers with an
HTTP status, for example 429, 502, 503 or 504, the line has a `statusCode` and no `reason`, so provider-outage does
not count it. This is business as usual: every status except 400, 404 and 413 is retried on the task's retry
schedule, and the notification ends as `notification-sent` if a retry succeeds, or as `notification-failed`
(counted by requester-outcomes as `provider-failed`) once the retries run out. A permanent Office 365 failure is
also logged without a `reason`, so it is not counted either.

**Not counted by either rule:** attachment downloads and Gov.Notify status checks (`operation` = `download` /
`status-check`) and calls to `blob-storage` / `material`. These reach requesters only via the `failed` outcome they
eventually cause.

**Dead-lettered commands are caught by the queue alerts, not the log alerts.** A command can be dead-lettered
without NG logging a `rejected` or `failed` outcome, so neither log rule sees it:

- no `ReplyTo`: dead-lettered at once (`MissingReplyTo`, logged at WARN)
- the `notification-validation-failed` event cannot be published, or ingestion fails: logged at ERROR as
  `Transient failure … abandoning for redelivery`, and dead-lettered by Service Bus once the queue's max
  delivery count is reached (the `rejected` line is only logged after a successful publish)

## Queue alerts

These are covered by Azure Monitor metric alerts on the command queues (`queue_alerts` in the same repo). They notify
Notify Gateway Support. In STE they watch `steccm01` and `steccm13` `ng-send-email` and `ng-send-letter` on `sbsteccm01`.
They are split per queue (`EntityName`), so each queue fires and resolves as its own alert, and the email names the queue.

| Alert | Threshold | Metric (aggregation) | Checked | Fires when |
|---|---|---|---|---|
| **ng-queues-dead-letter** | > 0, i.e. at least 1, for the whole 5 min | `DeadletteredMessages` (minimum) | every minute | A message has sat in a command queue's dead-letter sub-queue for 5 minutes |
| **ng-queues-backlog** | > 10 for the whole 15 min | `ActiveMessages` (minimum) | every minute | Commands are waiting: NG is not keeping up, or not running |

**Both alerts measure the queue's length, not how fast messages arrive.**

- **They count what is in the queue now.** `DeadletteredMessages` and `ActiveMessages` are the number of messages in
  the queue at that moment. A minimum above the threshold means the count stayed above it for the whole window.
- **A new dead letter while the alert is fired sends no email.** The alert is stateful: it sends one email when it
  fires and one when it resolves. It stays fired until someone clears the dead-letter sub-queue.
- **Old dead letters are not re-reported.** Dead letters that are never cleared keep the alert fired. Delete or
  resubmit them once handled, so that the next dead letter raises a new alert.

**Length metrics and counter metrics.** Service Bus metrics are one of two kinds. Every metric is then time-aggregated:
the 1-minute points in the alert window are combined into one value, which is compared with the threshold.

| | Length (a snapshot) | Counter (activity in a period) |
|---|---|---|
| Each 1-minute point means | Messages in the queue at that moment | Messages that arrived or left during that minute |
| Metrics | `ActiveMessages`, `DeadletteredMessages`, `Messages`, `ScheduledMessages`, `Size` | `IncomingMessages`, `OutgoingMessages`, `CompleteMessage`, `AbandonMessage` |
| Aggregations | Average, Minimum, Maximum. No Total: summing snapshots counts the same messages again | Total, plus the others |

**There is no arrival-rate alert for the dead-letter sub-queue.**

- **Main queue: possible, not configured.** Service Bus reports arrivals per main queue (`IncomingMessages`,
  aggregation Total), so an alert such as "more than N commands arrived in a minute" could be added to
  `queue_alerts`.
- **Dead-letter sub-queue: no metric.** Azure Monitor reports no metrics for the dead-letter sub-queue as an entity
  of its own. Dead-lettering is not counted in `IncomingMessages`. `DeadletteredMessages` only supports Average,
  Minimum and Maximum.
- **A dead-letter trend alert is not possible.** A dynamic-threshold rule on `DeadletteredMessages` was tried. Azure
  checks it twice, and the two checks contradict each other:
  - with `Maximum`, the dynamic-threshold check refuses it: `400 BadRequest: Dynamic alert currently only supports Total
    aggregation type for this metric`;
  - with `Total`, the metric's own definition refuses it: `400 BadRequest: Time aggregation must be one of [Average,
    Minimum, Maximum]`.

  No aggregation passes both checks, so the rule was dropped. `ActiveMessages` is the same kind of metric and is
  expected to fail the same way (not tested). Counter metrics such as `IncomingMessages` support Total, so a dynamic
  threshold should work on them. Even if Azure allowed it, a dynamic threshold suits the dead-letter sub-queue badly:
  it learns a long-uncleared count as normal and stops alerting.
- **What a dead-letter rate would need:**
  - a log search alert that compares `DeadletteredMessages` at the start and end of each window in `AzureMetrics`.
    This needs the namespace's diagnostic settings to send metrics to Log Analytics.
  - or NG logging every abandon as well as the `MissingReplyTo` dead-letter. Only the `MissingReplyTo` case is logged
    when the message is dead-lettered.

**Where fired queue alerts appear.**

- **The rules** are in `RG-<ENV>-NG-ALERTS-01`, under Alert rules.
- **Fired and resolved alerts** are listed under the target resource:
  - the namespace (`sbsteccm01` in `RG-STE-SBNS-01`) → Monitoring → Alerts;
  - or Monitor → Alerts at subscription scope, filtered by alert rule.
- **Include Resolved** in the "Alert condition" filter to see past alerts. The alert's `EntityName` dimension names
  the queue.
