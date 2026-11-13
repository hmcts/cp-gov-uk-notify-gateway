# Client onboarding — moving from `notification-notify` to `cp-gov-uk-notify-gateway`

This guide is for a context that sends email or letters through the legacy
`cpp-context-notification-notify` and is moving to the gateway (NG). It lists every change the client
must make. The behaviour behind each change is recorded in
[ADR-002](pipeline/adrs/002-deviations-from-legacy-notify-context.md); only the deviations marked
**Client impact: Yes** there need client work.

`mi-reportdata` is the reference client: its queues and grants are already in place in every environment
and can be copied.

## Summary of changes

| # | Change | Legacy | NG | ADR |
|---|--------|--------|----|-----|
| 1 | Transport | REST `POST /notifications/{notificationId}` | Service Bus message on a command queue | D1, D11 |
| 2 | Command queue | — | email `<stack>.ng-send-email`, letter `<stack>.ng-send-letter` | D1, D11 |
| 3 | Result delivery | `public.notificationnotify.events.*` on the public event topic | message on the client's **own reply queue**, named in `ReplyTo` | D13 |
| 4 | Result event name | `public.notificationnotify.events.notification-sent` / `-failed` | `notification-sent` / `notification-failed` in the message `subject` | D13 |
| 5 | `notificationId` | in the REST path | in the message body | D1, D11 |
| 6 | Attachment | `fileId` (file-service) | `fileUri` (Azure Blob URI); `materialUrl` unchanged | D3, D4 |
| 7 | Permissions | Drools (System Users group) | Azure RBAC on the queues, plus NG read access on the client's blob container | D1, D3 |
| 8 | Invalid request | synchronous `400` | `notification-validation-failed` event on the reply queue; existing `400` handling moves to it ([§2](#2-handling-a-rejected-request)) | D14 |
| 9 | `ReplyTo` | n/a (REST answers the caller) | **required** on every command; a command without it is dead-lettered unprocessed | D14 |
| 10 | Status query | `GET /notification/{notificationId}` | removed; use the result events | D6 |

## 1. Infrastructure

Each piece is per environment and per stack. In the names below, `<env>` is `ste`, `dev`, `sit`, `nft`,
`prp` or `prd`, `<stack>` is the stack name (e.g. `steccm01`), and `<client>` is the client context
(e.g. `staging-dvla`).

### 1.1 Reply queue (new)

Create one queue for the client's result events:

- **Name:** `<client>-notification-result`. It is stack-prefixed at provisioning, so it is
  `<stack>.<client>-notification-result` (e.g. `steccm01.mi-reportdata-notification-result`).
- **Namespace:** the env-common bus `sb<env>ccm01`, the same namespace as the NG command queues.
- **Where:** `cpp-terraform-azurerm-servicebus`, `vars/<env>.tfvars`. Copy the
  `mi-reportdata-notification-result` entry (max delivery count `10`, dead-letter on expiry, TTL `P14D`).

The command queues `ng-send-email` and `ng-send-letter` already exist; the client does not create them.

### 1.2 Service Bus RBAC

In `cpp-aks-deploy`, `ansible/group_vars/<env>/ccm_workload_identities.yaml.j2`:

| Identity | Queue | Role |
|----------|-------|------|
| client | `{{ stack }}.ng-send-email` (email) | `AzureServiceBusDataSender` |
| client | `{{ stack }}.ng-send-letter` (letter) | `AzureServiceBusDataSender` |
| client | `{{ stack }}.<client>-notification-result` | `AzureServiceBusDataReceiver` |
| `govuknotifygateway` | `{{ stack }}.<client>-notification-result` | `AzureServiceBusDataSender` |

The last grant goes on **NG's** identity. Without it NG cannot deliver the result events to the reply queue.

```yaml
    <client>:
      "{{ stack }}.ng-send-email":                        # email clients
        resource_group: "RG-STE-SBNS-01"
        role_definition_name: "AzureServiceBusDataSender"
        resource_type: "Microsoft.ServiceBus"
        resource_kind: "namespaces/sb{{ env }}ccm01/queues"
      "{{ stack }}.ng-send-letter":                       # letter clients
        resource_group: "RG-STE-SBNS-01"
        role_definition_name: "AzureServiceBusDataSender"
        resource_type: "Microsoft.ServiceBus"
        resource_kind: "namespaces/sb{{ env }}ccm01/queues"
      "{{ stack }}.<client>-notification-result":
        resource_group: "RG-STE-SBNS-01"
        role_definition_name: "AzureServiceBusDataReceiver"
        resource_type: "Microsoft.ServiceBus"
        resource_kind: "namespaces/sb{{ env }}ccm01/queues"
    govuknotifygateway:
      "{{ stack }}.<client>-notification-result":
        resource_group: "RG-STE-SBNS-01"
        role_definition_name: "AzureServiceBusDataSender"
        resource_type: "Microsoft.ServiceBus"
        resource_kind: "namespaces/sb{{ env }}ccm01/queues"
```

The resource group differs per environment; copy it from the `mi-reportdata` entries in the same file.

### 1.3 Blob read access for `fileUri` attachments

Only clients that attach a document by `fileUri`. NG downloads the blob with its own managed identity, so
NG needs read access on the client's container. Grant it on the narrowest prefix that holds the attachments,
not the whole container:

```yaml
    govuknotifygateway:
      container_access_rbac:
        "<client>":                              # container = {{ stack }}-<client>
          storage_account: "sastefilestore"
          resource_group: "RG-DEV-SVC-01"       # correct for STE: sastefilestore lives in RG-DEV-SVC-01
          grants:
            - name: "documents-read"
              role_definition_name: "StorageBlobDataReader"
              prefix_access: "read"
              prefix: [ "<path>/documents/" ]   # blobs under this prefix only
```

`prefix_access: "read"` produces an ABAC condition that allows blob reads under the prefix and blocks
listing. For example, staging-dvla grants `sdg/documents/*`.

The storage account host must also be on NG's allow-list, `CP_NG_BLOB_ALLOWED_HOSTS` in
`cpp-flux-config`. STE allows `sastefilestore.blob.core.windows.net`. A `fileUri` on any other host is
refused with a permanent failure (D4).

Clients that attach by `materialUrl`, and all letter clients, need no blob grant: NG reads those
documents through the Material service.

## 2. Handling a rejected request

Set `ReplyTo` on **every** command to the client's reply queue (`<stack>.<client>-notification-result`).
NG dead-letters a command that has no `ReplyTo`, without reading it: nothing is sent, nothing is recorded, and
no event is returned, because there is nowhere to return it.

When a command's payload cannot be read (not valid JSON, or a field of the wrong type such as a
`notificationId` that is not a UUID) or fails validation (a required field missing or invalid, e.g. no
`sendToAddress`, `templateId` or `notificationId` for email, no `letterUrl` for letters), NG does not send or
record it. It returns a `notification-validation-failed` event to the reply queue and completes the command
message. If the event cannot be sent, the command is redelivered by Service Bus and dead-lettered once the
queue's max delivery count (10) is reached. A command with no `ReplyTo` is dead-lettered with reason
`MissingReplyTo`.

| Message part | Value |
|--------------|-------|
| `subject` | `notification-validation-failed` |
| body | JSON, schema `contracts/notification-validation-failed.schema.json` |

```json
{
  "reason": "PAYLOAD_VALIDATION_FAILED",
  "details": "sendToAddress: must not be blank",
  "originalEventPayload": "{\"notificationId\":\"6f1c…\",\"templateId\":\"0b7e…\"}"
}
```

| Field | Meaning |
|-------|---------|
| `reason` | always `PAYLOAD_VALIDATION_FAILED` |
| `details` | every validation failure as `field: message`, comma-separated, sorted by field; for a payload that could not be read, the JSON parser's message, prefixed with the field when known |
| `originalEventPayload` | the command body exactly as sent, **as a JSON string** |

**Existing `400` handling moves to this event.** With legacy, an invalid request failed synchronously with
`400 Bad Request` on the REST call. With NG, sending the command to the queue always succeeds, and the rejection
arrives later as this event. Any behaviour the client has for that `400` must be kept by handling
`notification-validation-failed` instead: for example, logging or alerting on it, marking the client's own
record as failed, or stopping a retry. The `400` path no longer fires, so a client that only checks the HTTP
response silently loses those rejections.

To consume it:

- Route on the message `subject`, alongside `notification-sent` and `notification-failed`. A handler that
  only expects those two will otherwise see a body it cannot map.
- Correlate by parsing `originalEventPayload` and reading the client's own `notificationId` from it. The
  event has no top-level `notificationId`: the command was rejected, so NG never accepted that id. If the
  original payload had no `notificationId`, the client cannot correlate the event; log it with `details`.
- Treat it as final. Resending the same payload fails the same way; fix the payload first.
- Validate against `contracts/command-send-email-notification.schema.json` /
  `command-send-letter-notification.schema.json` before sending, so rejections stay rare.

## 3. Alerts to your team

NG logs one line for each request it rejects and each notification that fails, with the reply queue it was for
and a reason (PEG-3718, PEG-3719). An Azure Monitor log search alert counts these lines for each reply queue and
emails the team that owns it. The line never holds the request, the recipient's address or the provider's error
text. Your team's email address is added to the alert when your service is onboarded.

| JSON field | Value |
|------------|-------|
| `replyQueue` | the `ReplyTo` queue of the request |
| `notificationOutcome` | `rejected` (the request was not accepted) or `failed` (it was accepted and then failed) |
| `outcomeReason` | see below |
| `notificationChannel` | `email` or `letter` |

| `outcomeReason` | When |
|-----------------|------|
| `unreadable-request` | the request could not be read (not valid JSON, or a field of the wrong type) |
| `invalid-request` | the request failed validation |
| `attachment-not-found` | the attachment does not exist (`404` from blob storage, Material or the document link) |
| `attachment-access-refused` | access to the attachment was refused (`401` or `403`) |
| `attachment-too-large` | the attachment is over the size limit |
| `attachment-bad-link` | the link is not one NG may use: not on the Material service, not an allowed blob host, a document link that is not `https` Azure Blob Storage, or a request Material rejects (`400`) |
| `attachment-unavailable` | the attachment could not be downloaded before the retry window ended |
| `provider-rejected` | GOV.UK Notify or Office 365 rejected the notification |
| `provider-unreachable` | GOV.UK Notify or Office 365 could not be reached before the retry window ended |
| `provider-no-response` | GOV.UK Notify or Office 365 did not respond before the retry window ended |
| `provider-failed` | GOV.UK Notify or Office 365 kept failing until the retry window ended |
| `delivery-failed` | GOV.UK Notify reported that the email or letter could not be delivered |
| `status-unknown` | GOV.UK Notify had not finished with the notification when the retry window ended |

A missing attachment or one NG is refused access to fails straight away; a network problem fetching it is
retried until the retry window ends. Each line matches a `notification-validation-failed` or
`notification-failed` event on your reply queue.
