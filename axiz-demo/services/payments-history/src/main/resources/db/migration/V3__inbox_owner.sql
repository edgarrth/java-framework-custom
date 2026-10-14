-- axiz 0.1 review: every inbox registration records the delivery that owns its lease, so a
-- delivery whose lease was taken over cannot complete or release the new owner's registration.
-- Applied versions are never edited: this migration upgrades databases created with V1.
-- Rows without owner belong to deliveries of the previous version: they are taken over when their
-- lease ends, and processed rows stay processed.
ALTER TABLE axiz_inbox ADD COLUMN IF NOT EXISTS owner VARCHAR(64);
