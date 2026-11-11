CREATE TABLE profiles (
    owner_sub    text PRIMARY KEY,
    name         text        NOT NULL,
    public_token text        NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now()
);
