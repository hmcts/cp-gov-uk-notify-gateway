Feature: Letter notification delivery

  Recipients without an email address are sent a printed letter: once a
  send-letter-notification command is raised, the gateway fetches the letter's
  PDF from the Material service and sends it to Gov.UK Notify as a precompiled
  letter, first class when asked, otherwise second class. The letter is
  recorded as sent once Gov.UK Notify has received it for printing, which can
  take days after it is first accepted. When Gov.UK Notify fails the letter's
  validation it is sent again, up to five times, before the notification fails.
  When the letter cannot be fetched or Gov.UK Notify will not take it, the
  notification fails, after retries if the problem may pass. The
  originating context names a reply queue on every command and the gateway
  returns the outcome to it as a result event. A command that cannot be read
  or fails validation is rejected and the reason returned to the originator; a command without a
  reply queue is set aside unprocessed, because its outcome could not be
  returned.

  Background:
    Given the originator provides a reply queue

  @smoke
  Scenario: A letter is sent second class, recorded as sent and its outcome returned to the originator
    Given a send-letter-notification command for a letter held by the Material service
    When the gateway processes the command
    Then the document is fetched from the Material service on behalf of the notification
    And the letter is sent via the Gov.UK Notify provider
    And the notification is recorded as a letter with its letter link
    And the notification is recorded as SENT
    And a notification-sent result event for the letter is published to the originator's reply queue

  @smoke
  Scenario: A first-class letter is sent first class
    Given a first-class send-letter-notification command for a letter held by the Material service
    When the gateway processes the command
    Then the letter is sent via the Gov.UK Notify provider
    And the notification is recorded as SENT
    And a notification-sent result event for the letter is published to the originator's reply queue

  @regression
  Scenario: A letter the provider accepts is followed until the provider receives it
    Given a send-letter-notification command for a letter held by the Material service
    And the provider accepts the letter before receiving it
    When the gateway processes the command
    Then the letter is sent via the Gov.UK Notify provider
    And the notification is recorded as SENT
    And a notification-sent result event for the letter is published to the originator's reply queue

  @regression
  Scenario: A letter that fails the provider's validation once is sent again and recorded as sent
    Given a send-letter-notification command for a letter held by the Material service
    And the provider fails the letter's validation once before receiving it
    When the gateway processes the command
    Then the notification is recorded as SENT
    And the letter is sent to the Gov.UK Notify provider 2 times
    And a notification-sent result event for the letter is published to the originator's reply queue

  @regression
  Scenario: A letter that keeps failing the provider's validation is sent five more times and then failed
    Given a send-letter-notification command for a letter held by the Material service
    And the provider fails the letter's validation every time
    When the gateway processes the command
    Then the notification is recorded as FAILED
    And the letter is sent to the Gov.UK Notify provider 6 times
    And a notification-failed result event for the letter saying no automatic resends remain is published to the originator's reply queue

  @regression
  Scenario: A letter the provider is still processing is polled until the provider receives it
    Given a send-letter-notification command for a letter held by the Material service
    And the provider is still processing the letter before receiving it
    When the gateway processes the command
    Then the notification is recorded as SENT
    And the letter is sent to the Gov.UK Notify provider 1 times
    And a notification-sent result event for the letter is published to the originator's reply queue

  @regression
  Scenario: A letter the provider reports as permanently failed is recorded as failed
    Given a send-letter-notification command for a letter held by the Material service
    And the provider reports the letter as permanently failed
    When the gateway processes the command
    Then the notification is recorded as FAILED
    And a notification-failed result event for the letter is published to the originator's reply queue

  @regression
  Scenario: A letter the provider rejects as invalid is failed without retrying
    Given a send-letter-notification command for a letter held by the Material service
    And the provider rejects the letter as invalid
    When the gateway processes the command
    Then the notification is recorded as FAILED
    And the letter is sent to the Gov.UK Notify provider 1 times
    And a notification-failed result event for the letter with status 400 is published to the originator's reply queue

  @regression
  Scenario: A letter the provider cannot take is retried and failed once retries run out
    Given a send-letter-notification command for a letter held by the Material service
    And the provider is unavailable for letters
    When the gateway processes the command
    Then the notification is recorded as FAILED
    And the letter is sent to the Gov.UK Notify provider 4 times
    And a notification-failed result event for the letter with status 500 is published to the originator's reply queue

  @regression
  Scenario: A letter whose document stays unavailable is failed once retries run out and never sent
    Given a send-letter-notification command for a letter whose Material document stays unavailable
    When the gateway processes the command
    Then the notification is recorded as FAILED
    And no letter is sent via the Gov.UK Notify provider
    And a notification-failed result event for the letter with status 500 is published to the originator's reply queue

  @regression
  Scenario: A letter whose document is briefly unavailable is sent once it becomes available
    Given a send-letter-notification command for a letter whose Material document is briefly unavailable
    When the gateway processes the command
    Then the letter is sent via the Gov.UK Notify provider
    And the notification is recorded as SENT
    And a notification-sent result event for the letter is published to the originator's reply queue

  Scenario: A letter command that fails validation is rejected and the reason returned to the originator
    Given a send-letter-notification command with no letter link
    When the gateway processes the command
    Then a notification-validation-failed result event saying "letterUrl: must not be blank" is published to the originator's reply queue
    And no notification is recorded
    And no letter is sent via the Gov.UK Notify provider

  Scenario: A letter command that cannot be read is rejected and the reason returned to the originator
    Given a send-letter-notification command that is not valid JSON
    When the gateway processes the command
    Then a notification-validation-failed result event explaining why the command could not be read is published to the originator's reply queue
    And no notification is recorded
    And no letter is sent via the Gov.UK Notify provider

  Scenario: A letter with no reply queue is set aside unprocessed
    Given a send-letter-notification command for a letter held by the Material service
    And the originator provides no reply queue
    When the gateway processes the command
    Then the command is set aside for investigation
    And no notification is recorded
    And no letter is sent via the Gov.UK Notify provider
