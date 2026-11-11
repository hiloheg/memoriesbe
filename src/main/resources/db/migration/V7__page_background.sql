ALTER TABLE profiles
    ADD COLUMN bg_kind    text NOT NULL DEFAULT 'default',
    ADD COLUMN bg_value   text NOT NULL DEFAULT '',
    ADD COLUMN bg_pattern text NOT NULL DEFAULT 'none',
    ADD COLUMN bg_effect  text NOT NULL DEFAULT 'none';
