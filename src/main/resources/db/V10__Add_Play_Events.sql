-- One row per track actually played, feeding the "Hot in this server" + listening-stats feature.
-- Written when a track starts in voice (/play, /play-playlist) and when /song posts a track. user_id
-- is nullable (a play may have no attributable requester); artwork_url lets the stats UI show covers.
CREATE TABLE play_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT,
    user_name TEXT,
    title TEXT,
    author TEXT,
    uri TEXT,
    artwork_url TEXT,
    source TEXT NOT NULL, -- 'voice' (/play, /play-playlist) | 'song' (/song)
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX idx_play_events_uri ON play_events (uri);
CREATE INDEX idx_play_events_user ON play_events (user_id);
