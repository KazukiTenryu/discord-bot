CREATE TABLE playlist_tracks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    user_name TEXT NOT NULL,
    title TEXT NOT NULL,
    author TEXT,
    uri TEXT NOT NULL,
    duration_ms INTEGER,
    thumbnail_url TEXT,
    added_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX idx_playlist_tracks_user ON playlist_tracks (user_id);
