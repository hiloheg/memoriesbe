CREATE TABLE events (
    id           uuid PRIMARY KEY,
    owner_sub    text        NOT NULL,
    name         text        NOT NULL,
    public_token text        NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX events_owner_idx ON events (owner_sub, created_at DESC);

CREATE TABLE cameras (
    id            uuid PRIMARY KEY,
    event_id      uuid        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name          text        NOT NULL,
    sftp_username text        NOT NULL UNIQUE,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cameras_event_idx ON cameras (event_id);
