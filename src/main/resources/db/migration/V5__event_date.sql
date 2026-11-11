ALTER TABLE events ADD COLUMN event_date date;
UPDATE events SET event_date = created_at::date WHERE event_date IS NULL;
ALTER TABLE events ALTER COLUMN event_date SET NOT NULL;
