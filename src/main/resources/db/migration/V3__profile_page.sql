ALTER TABLE profiles
    ADD COLUMN slug           text UNIQUE,
    ADD COLUMN tagline        text,
    ADD COLUMN bio            text,
    ADD COLUMN accent         text,
    ADD COLUMN website        text,
    ADD COLUMN instagram      text,
    ADD COLUMN avatar_key     text,
    ADD COLUMN google_picture text;

ALTER TABLE events ADD COLUMN slug text;
UPDATE events SET slug = 'event-' || substr(id::text, 1, 8) WHERE slug IS NULL;
ALTER TABLE events ALTER COLUMN slug SET NOT NULL;
CREATE UNIQUE INDEX events_owner_slug_idx ON events (owner_sub, slug);
