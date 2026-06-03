-- Canonical track name resolved from the iTunes Search API (e.g. "Bohemian Rhapsody" instead of the
-- raw YouTube title "Queen - Bohemian Rhapsody (Official Video) [HD]"). Nullable: a lookup miss
-- leaves it NULL and callers fall back to the raw `title`. The raw `title` is kept for lyrics
-- lookups, search, and download filenames.
ALTER TABLE playlist_tracks ADD COLUMN track_name TEXT;
