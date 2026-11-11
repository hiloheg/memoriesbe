-- The event creator can stop a guest from adding more photos. Their existing photos stay until deleted one by one.
alter table event_guests add column blocked_at timestamptz;
