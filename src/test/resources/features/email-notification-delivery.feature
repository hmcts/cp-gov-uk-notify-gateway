Feature: Email notification delivery

  Report recipients reliably receive their email notification: once a
  send-email-notification command is raised, the gateway delivers the email
  through the notification provider and records the notification as sent. The
  attachment is either a file the originator stored or a document held by the
  Material service; when both are given, the Material document is attached. When
  the attachment is larger than Gov.UK Notify accepts, the email is sent through Office 365 instead. When
  the attachment cannot be retrieved, the gateway fails the notification rather
  than sending a broken email; a Material document that is only briefly
  unavailable is retried, and the notification fails only once retries run out. The originating context names a reply
  queue on every command and the gateway returns the outcome to it as a result
  event. A command that cannot be read or fails validation is rejected and the
  reason returned to the originator; a command without a reply queue is set aside unprocessed,
  because its outcome could not be returned.

  Background:
    Given the originator provides a reply queue

  @smoke
  Scenario: A delivered notification is recorded as sent and its outcome returned to the originator
    Given a send-email-notification command for a recipient with an attachment
    When the gateway processes the command
    Then the email is sent via the Gov.UK Notify provider
    And the delivery status is polled from the provider
    And the notification is recorded as SENT
    And a notification-sent result event is published to the originator's reply queue

  Scenario: A notification with an unretrievable attachment is failed, no email is sent, and the failure returned to the originator
    Given a send-email-notification command whose attachment is missing
    When the gateway processes the command
    Then the notification is recorded as FAILED
    And no email is sent via the Gov.UK Notify provider
    And a notification-failed result event is published to the originator's reply queue

  Scenario: A notification the provider reports as permanently failed is recorded as failed and the outcome returned to the originator
    Given a send-email-notification command for a recipient with an attachment
    And the provider reports the delivery as permanently failed
    When the gateway processes the command
    Then the email is sent via the Gov.UK Notify provider
    And the notification is recorded as FAILED
    And a notification-failed result event is published to the originator's reply queue

  Scenario: A notification command that fails validation is rejected and the reason returned to the originator
    Given a send-email-notification command with no recipient address
    When the gateway processes the command
    Then a notification-validation-failed result event saying "sendToAddress: must not be blank" is published to the originator's reply queue
    And no notification is recorded
    And no email is sent via the Gov.UK Notify provider

  Scenario: A notification command that cannot be read is rejected and the reason returned to the originator
    Given a send-email-notification command whose template id cannot be read
    When the gateway processes the command
    Then a notification-validation-failed result event explaining why the command could not be read is published to the originator's reply queue
    And no notification is recorded
    And no email is sent via the Gov.UK Notify provider

  Scenario: A notification with no reply queue is set aside unprocessed
    Given a send-email-notification command for a recipient with an attachment
    And the originator provides no reply queue
    When the gateway processes the command
    Then the command is set aside for investigation
    And no notification is recorded
    And no email is sent via the Gov.UK Notify provider

  @smoke
  Scenario: A notification with a Material document link is delivered with the document attached and its outcome returned to the originator
    Given a send-email-notification command for a recipient with a Material document link
    When the gateway processes the command
    Then the document is fetched from the Material service on behalf of the notification
    And the email is sent via the Gov.UK Notify provider with the Material document attached
    And the Material document link is recorded on the notification
    And the notification is recorded as SENT
    And a notification-sent result event is published to the originator's reply queue

  @regression
  Scenario: A notification whose Material document is larger than Gov.UK Notify accepts is sent via Office 365 and its outcome returned to the originator
    Given a send-email-notification command for a recipient with a Material document larger than Gov.UK Notify accepts
    When the gateway processes the command
    Then the email is sent via the Office 365 provider with the Material document attached
    And no email is sent via the Gov.UK Notify provider
    And the notification is recorded as SENT
    And a notification-sent result event for the Office 365 email is published to the originator's reply queue

  @regression
  Scenario: A Material link that already points at the Material service is used as given
    Given a send-email-notification command for a recipient with a Material document link that already points at the Material service
    When the gateway processes the command
    Then the document is fetched from the Material service on behalf of the notification
    And the email is sent via the Gov.UK Notify provider with the Material document attached
    And the notification is recorded as SENT

  @regression
  Scenario: When both a Material link and a file are given, only the Material document is attached
    Given a send-email-notification command for a recipient with both a Material document link and a file
    When the gateway processes the command
    Then the email is sent via the Gov.UK Notify provider with the Material document attached
    And the notification is recorded as SENT

  @regression
  Scenario Outline: A Material attachment that cannot be sent is failed, no email is sent, and the failure returned to the originator
    Given a send-email-notification command for a recipient with <material problem>
    When the gateway processes the command
    Then the notification is recorded as FAILED
    And no email is sent via the Gov.UK Notify provider
    And a notification-failed result event is published to the originator's reply queue

    Examples:
      | material problem                                  |
      | a Material link to an unrecognised address        |
      | a Material document too large to send             |
      | a Material link that Material rejects as invalid  |

  @regression
  Scenario: A Material document that is briefly unavailable is sent once it becomes available
    Given a send-email-notification command for a recipient with a Material document that is briefly unavailable
    When the gateway processes the command
    Then the email is sent via the Gov.UK Notify provider with the Material document attached
    And the notification is recorded as SENT

  @regression
  Scenario: A Material document the gateway is not allowed to fetch is failed once retries run out
    Given a send-email-notification command for a recipient with a Material document the gateway is not allowed to fetch
    When the gateway processes the command
    Then the notification is recorded as FAILED
    And the Material service is asked for the document again on each retry
    And no email is sent via the Gov.UK Notify provider
    And a notification-failed result event with status 403 is published to the originator's reply queue
