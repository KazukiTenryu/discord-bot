-- Optional custom cover image per user's playlist. Set by the owner via the web player (token-auth);
-- absent rows fall back to the generated gradient cover. Stored as a BLOB so it persists in bot.db.
CREATE TABLE playlist_images (
    user_id TEXT NOT NULL PRIMARY KEY,
    content_type TEXT NOT NULL,
    data BLOB NOT NULL,
    updated_at TEXT DEFAULT (datetime('now', 'localtime'))
);
