# [NG-S15] Reject unusable commands without recording them

> Jira: [PEG-3697](https://tools.hmcts.net/jira/browse/PEG-3697)

## User story
As an **originating context sending notification commands to the gateway**,
I want **a command the gateway cannot read or validate answered on my reply queue with the reason, and a
command with no reply queue set aside rather than silently processed**,
so that **I find out about a bad request the way a REST caller found out from a `400`, and no command is
processed whose outcome nobody can receive**.

## Background
Moving ingress from REST to Service Bus (ADR-002 D1, D11) removed the synchronous `400`. Before this story a
bad command was dead-lettered with no word to the caller, and a command without `ReplyTo` was processed with
its result events dropped. This story replaces both with the behaviour recorded in ADR-002 D14. It applies to
`ng-send-email` and `ng-send-letter` alike.

## Acceptance criteria
- [x] AC-042: Given a send-email or send-letter command with no `ReplyTo`, when it is consumed, then it is
  dead-lettered with reason `MissingReplyTo` and nothing is sent or recorded.
- [x] AC-043: Given a command with a `ReplyTo` that fails validation, when it is consumed, then a
  `notification-validation-failed` event (`reason` `PAYLOAD_VALIDATION_FAILED`, `details` as `field: message`
  sorted by field, `originalEventPayload` the body as received) is sent to `ReplyTo`, the message is
  completed, and nothing is sent or recorded.
- [x] AC-044: Given a command with a `ReplyTo` whose body is not valid JSON or has a field of the wrong type,
  when it is consumed, then the same event is sent with the parser's message (prefixed with the field when
  known) as `details`, the message is completed, and nothing is sent or recorded.
- [x] AC-045: Given the `notification-validation-failed` event cannot be sent, when the command is consumed,
  then the message is abandoned for Service Bus redelivery and dead-lettered once the queue's max delivery
  count is reached.
- [x] AC-046: Given any rejected command, when it is consumed, then no database transaction is opened.

## NFR links
- NFR-001 (Behavioural parity): **deliberate deviation**, recorded as ADR-002 D14. Legacy answered the same
  requests with a synchronous `400`; `notification-validation-failed` has no legacy equivalent and sits
  outside the golden-master result-event contract.

## Out of scope for this story
- Restricting which reply queues a caller may name (an allow-list on `ReplyTo`).
- Bounding `ResultEventPublisher`'s per-queue sender cache.
- Producer-side changes: every client must set `ReplyTo` before this ships to its environment.

## Definition of done
- [x] All ACs covered by automated tests: BDD scenarios in `email-notification-delivery.feature` and
  `letter-notification-delivery.feature`; consumer boundary tests (`SendEmailConsumerIntegrationTest`,
  `SendLetterConsumerIntegrationTest`); `ValidationFailureNotifierTest`.
- [x] Contract `contracts/notification-validation-failed.schema.json` published and asserted in BDD.
- [x] ADR-002 D14, the client onboarding guide and the README updated.
- [ ] Code reviewed and approved.
- [ ] Every client producing to the gateway in the target environment confirmed to set `ReplyTo`.
- [ ] Deployed to and verified on STE.

## Notes / open questions
- **FR/AC traceability:** FR-025 → AC-042 … AC-046. Supersedes AC-015 (FR-007) and the "only if `ReplyTo`
  is present" clause of AC-032 (NG-S11).
- No implementation-plan artifact was produced: the story was written after the change, against the code
  and ADR-002 D14.
