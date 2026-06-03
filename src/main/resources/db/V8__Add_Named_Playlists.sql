-- Named playlists. Each user may own several; exactly one is their default (is_default = 1).
-- Tracks now belong to a single playlist via playlist_tracks.playlist_id ("add to X" copies a row,
-- "move to X" reassigns it). Existing one-playlist-per-user data is migrated into a default playlist.
CREATE TABLE playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    user_name TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    is_default INTEGER NOT NULL DEFAULT 0,
    created_at TEXT DEFAULT (datetime('now', 'localtime')),
    updated_at TEXT DEFAULT (datetime('now', 'localtime')),
    UNIQUE (user_id, name)
);

CREATE INDEX idx_playlists_user ON playlists (user_id);

-- Add the FK column nullable: SQLite can't add a NOT NULL column without a constant default, nor an
-- inline REFERENCES to an existing table, so integrity is enforced in the application layer. The
-- index backs the per-playlist track query.
ALTER TABLE playlist_tracks ADD COLUMN playlist_id INTEGER;
CREATE INDEX idx_playlist_tracks_playlist ON playlist_tracks (playlist_id);

-- One default playlist per user that already has tracks or a web-player token. The latest known
-- display name for each user (the row with the greatest playlist_tracks.id, else the token's name)
-- labels the playlist's owner, mirroring how listOwners() picks a current name.
INSERT INTO playlists (user_id, user_name, name, is_default)
SELECT owners.user_id,
       COALESCE(latest.user_name, owners.user_name),
       'My Playlist',
       1
FROM (
    SELECT user_id, user_name FROM playlist_tracks
    UNION
    SELECT user_id, user_name FROM playlist_tokens
) AS owners
LEFT JOIN (
    SELECT t.user_id, t.user_name
    FROM playlist_tracks t
    JOIN (SELECT user_id, MAX(id) AS max_id FROM playlist_tracks GROUP BY user_id) m
      ON t.user_id = m.user_id AND t.id = m.max_id
) AS latest ON latest.user_id = owners.user_id
GROUP BY owners.user_id;

-- Point every existing track at its owner's new default playlist.
UPDATE playlist_tracks
SET playlist_id = (
    SELECT p.id FROM playlists p
    WHERE p.user_id = playlist_tracks.user_id AND p.is_default = 1
)
WHERE playlist_id IS NULL;
