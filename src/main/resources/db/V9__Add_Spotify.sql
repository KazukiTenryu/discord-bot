-- Stored Spotify OAuth tokens, one connection per Discord user (keyed by user_id like the web token).
-- expires_at is an ISO-8601 instant; the access token is refreshed when now >= expires_at.
CREATE TABLE spotify_accounts (
    user_id TEXT NOT NULL PRIMARY KEY,
    access_token TEXT NOT NULL,
    refresh_token TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    scope TEXT,
    spotify_user_id TEXT,
    updated_at TEXT DEFAULT (datetime('now', 'localtime'))
);

-- Short-lived CSRF state binding an OAuth round-trip to the Discord user who started it. The
-- callback is a top-level browser navigation that can't carry the X-Playlist-Token header, so the
-- state (minted during a token-authed /api/spotify/login) is the only identity link. Consumed
-- (deleted) on callback; rows older than the TTL are rejected.
CREATE TABLE spotify_oauth_state (
    state TEXT NOT NULL PRIMARY KEY,
    user_id TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now', 'localtime'))
);
