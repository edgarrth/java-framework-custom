-- axiz inbox upgrade (0.1 review): owner token of the delivery holding each lease.
-- Add it as a NEW Flyway migration of the service: never edit a migration already applied.
-- Existing in-progress rows have no owner: they are taken over when their lease ends.
ALTER TABLE axiz_inbox ADD COLUMN IF NOT EXISTS owner VARCHAR(64);
