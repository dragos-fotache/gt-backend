-- V1 catalogue rows remain bundled (the importer writes false); custom rows
-- require the same table to accept user-created records in a later feature.
ALTER TABLE exercises DROP CONSTRAINT exercises_is_user_created_check;
