-- Letter document link (letterUrl) from the send-letter-notification command, recorded at ingest (legacy
-- letter_url). Nullable: email notifications carry none, and rows written before this migration have none.
ALTER TABLE notification ADD COLUMN letter_url TEXT NULL;
