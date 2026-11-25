# AGENTS.md

Instructions for coding agents working in this repo. Start with [README.md](README.md) for what the service does.

## Build and test

Java 25 (Gradle toolchain), Spring Boot 4. The integration tests need Docker.

| Command | Runs |
|---|---|
| `./gradlew test` | Unit tests only (`*Test`, no Spring context, no containers) |
| `./gradlew integrationTest` | `*IntegrationTest` boundary tests and the Cucumber acceptance suite (Testcontainers) |
| `./gradlew pmdMain pmdTest` | PMD with `.github/pmd-ruleset.xml`. Violations fail the build |
| `./gradlew build` | Everything above: compile, unit and integration tests, PMD (so it needs Docker) |

Run `test` and PMD before finishing a change. Run `integrationTest` when you touch messaging, persistence,
tasks or a sender/downloader.

## Project structure

`src/main/java/uk/gov/hmcts/cp/notification/`, one package per concern:

| Package | Holds |
|---|---|
| `messaging` | Service Bus consumers for the send commands, `ReplyTo` handling, `notification-validation-failed` |
| `command` | Inbound command models (`SendEmailCommand`, `SendLetterCommand`) and their validation constraints |
| `service` | Ingest and notification status changes (`SENT` / `FAILED`) and the result events they publish |
| `task` | cp-task-manager tasks: send, status polling, retry policy |
| `sender` | Gov.Notify and Office 365 clients and their failure classification |
| `blob`, `material` | Attachment and letter PDF downloads (Azure Blob, Material) |
| `result` | Outbound result events and `ResultEventPublisher` |
| `persistence` | JPA entities and repositories; Flyway migrations live in `src/main/resources/db/migration` |
| `logging` | Structured log fields that the Azure Monitor alerts query |
| `failure`, `time`, `web` | Exception cause-chain helper, injectable clock, and the security config that leaves only actuator endpoints reachable |

Other places:

- `contracts/`: JSON schemas for the inbound commands and outbound events, including the legacy shapes
- `docs/pipeline/`: requirements, architecture, ADRs (`adrs/`) and user stories
- `docs/support/`: [ALERTS.md](docs/support/ALERTS.md) and [ERROR-HANDLING.md](docs/support/ERROR-HANDLING.md)
- `docs/Testing.md`: the full test conventions

## Test strategy

- **Unit** (`*Test`): pure logic with mocks. Most behaviour is covered here.
- **Integration** (`*IntegrationTest`, under `integration/`): `@SpringBootTest` against real containers
  (Postgres, Service Bus emulator, Azurite) and WireMock stubs for Gov.Notify, Office 365 and Material. Bases
  are in `integration/base`, stubs in `integration/stubs`.
- **Acceptance** (`acceptance/` and `src/test/resources/features/*.feature`): plain Cucumber, business scenarios
  only. One suite runner (`AcceptanceTest`); do not add another.
- A stub must assert the request it received field by field (path, headers, body, message properties), not only
  return a response. See `GovUkNotifyStubService`.
- Write the failing test first, then the code.

## Rules that are easy to break

- **Log field names are an alerting contract.** The names in `logging/LogField.java` and the enum values behind
  them are read by the alert queries in `cp-gov-uk-notify-gateway-terraform-alerts`. Renaming one silently breaks
  an alert. Update [ALERTS.md](docs/support/ALERTS.md) when you change what is logged.
- **Retry versus permanent is a deliberate decision per dependency.** Change it only in the classifiers
  (`GovNotifyFailureClassifier`, `Office365Exception`, `MaterialClient`/`SasDocumentFetcher`,
  `AttachmentDownloader`). Update [ERROR-HANDLING.md](docs/support/ERROR-HANDLING.md) when you do. A Gov.Notify
  call that gets no response is permanent on purpose: Gov.Notify does not de-duplicate, so a retry could send the
  notification twice.
- **Every task must catch its own failures.** An exception that escapes a task bypasses NG's retry policy, and
  the notification stays `QUEUED` with no outcome event.
- **Every notification ends with one event on `ReplyTo`:** `notification-sent`, `notification-failed` or
  `notification-validation-failed`.
- **Legacy parity is the default.** NG replaces `cpp-context-notification-notify`; deliberate differences are
  recorded in `docs/pipeline/adrs/002-deviations-from-legacy-notify-context.md`. Add a decision there before
  diverging.
- **Flyway migrations are append-only.** Add a new `V<n>__*.sql`; never edit one that has been released.
- **No secrets in the repo.** Configuration comes from environment variables (`CP_NG_*`) set in deployment.
