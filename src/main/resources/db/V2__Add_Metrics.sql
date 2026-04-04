CREATE TABLE metrics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event TEXT NOT NULL,
    happened_at TIMESTAMP NOT NULL,
    dimensions TEXT
);