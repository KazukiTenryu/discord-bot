-- Canonical track metadata resolved from the iTunes Search API when a song is added (and
-- backfilled for older rows). Both are nullable: a lookup miss leaves them NULL and callers fall
-- back to the YouTube channel name in `author`.
ALTER TABLE playlist_tracks ADD COLUMN artist TEXT;
ALTER TABLE playlist_tracks ADD COLUMN album TEXT;
