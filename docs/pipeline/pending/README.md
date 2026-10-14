# Pending decisions

Design decisions that are **analysed but not yet decided**. Each doc states the options, the constraint,
and a recommendation to confirm. Resolve, then fold the outcome into the relevant plan/design doc (or an
ADR if one is adopted) and remove it from here.

| Decision | Status | Gated by |
|---|---|---|
| [Attachment `fileName` validation](attachment-filename-validation.md) | ⏳ Decision pending | Where to validate (message vs blob HEAD) + fatal vs fallback |

The former query-API notes (`query-api-authentication.md`, `query-api-implementation.md`) were retired
when the query API was removed — see [ADR-002 D7](../adrs/002-deviations-from-legacy-notify-context.md).
