CREATE TABLE IF NOT EXISTS relationships (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id TEXT NOT NULL,
    type TEXT NOT NULL,
    a_user_id TEXT NOT NULL,
    b_user_id TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);

CREATE INDEX IF NOT EXISTS idx_relationships_a ON relationships(guild_id, a_user_id);
CREATE INDEX IF NOT EXISTS idx_relationships_b ON relationships(guild_id, b_user_id);
