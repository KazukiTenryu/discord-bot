CREATE TABLE  user_notes(
    id INTEGER PRIMARY KEY  AUTOINCREMENT,
    user_id TEXT NOT NULL,
    from_user TEXT NOT NULL,
    audit_type TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
)