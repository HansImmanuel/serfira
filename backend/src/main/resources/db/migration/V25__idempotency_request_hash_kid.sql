-- T28b / ADR-025: the PII key id the request fingerprint (request_hash) was computed under, so a replay after a
-- key rotation compares under the recorded key. NULL means a claim recorded before key ids existed; it was
-- hashed under the legacy key. There is deliberately no backfill.
-- NOT VALID skips the scan of existing rows (every one of them is NULL, so it would prove nothing) while the
-- check still applies to every new insert and update.
ALTER TABLE idempotency_keys
    ADD COLUMN request_hash_kid VARCHAR(16) NULL,
    ADD CONSTRAINT ck_idempotency_keys_request_hash_kid
        CHECK (request_hash_kid IS NULL OR request_hash_kid ~ '^[a-z0-9]{1,16}$') NOT VALID;
