-- Personal web-player access tokens. Each user gets a single secret token (rotated whenever they
-- run /playlist link) that the web player sends to authorise edits to that user's own playlist.
CREATE TABLE playlist_tokens (
    token TEXT NOT NULL PRIMARY KEY,
    user_id TEXT NOT NULL UNIQUE,
    user_name TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);
