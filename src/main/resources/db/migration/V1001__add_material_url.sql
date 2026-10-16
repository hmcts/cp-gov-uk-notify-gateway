-- Material document link (materialUrl) from the send-email-notification command, recorded at ingest.
-- Nullable: most notifications carry none, and rows written before this migration have none.
ALTER TABLE notification ADD COLUMN material_url TEXT NULL;
