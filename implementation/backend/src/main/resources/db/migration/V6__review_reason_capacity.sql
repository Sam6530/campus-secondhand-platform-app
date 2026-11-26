-- Keep accepted review reasons intact in the owner-visible record.
-- Existing V1-V5 scripts remain unchanged for checksum-safe upgrades.
ALTER TABLE verifications MODIFY COLUMN reason VARCHAR(1500);
ALTER TABLE items MODIFY COLUMN reason VARCHAR(1500);
