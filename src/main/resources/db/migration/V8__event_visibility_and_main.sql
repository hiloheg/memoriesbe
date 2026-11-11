-- true = listed on the photographer page; false = hidden there (the direct link still opens it)
ALTER TABLE events ADD COLUMN listed boolean NOT NULL DEFAULT true;

-- the one event the profile page shows on its own; cleared automatically when that event is deleted
ALTER TABLE profiles ADD COLUMN main_event_id uuid REFERENCES events (id) ON DELETE SET NULL;
