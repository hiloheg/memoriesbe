-- Guest uploads: the creator switches them on per event and sets how many photos each guest may add.
alter table events add column guest_uploads_enabled boolean not null default false;
alter table events add column guest_upload_limit int not null default 0 check (guest_upload_limit between 0 and 50);

-- A guest is a Google account that uploaded to one event; the name is shown as the photo's tag.
create table event_guests (
    id         uuid primary key,
    event_id   uuid        not null references events (id) on delete cascade,
    sub        text        not null,
    name       text        not null,
    created_at timestamptz not null default now(),
    unique (event_id, sub)
);
create index event_guests_event_idx on event_guests (event_id);

-- Every upload is recorded. Deleted photos keep their row (deleted_at) so they still count toward the guest's limit.
create table guest_uploads (
    id         uuid primary key,
    event_id   uuid        not null references events (id) on delete cascade,
    guest_id   uuid        not null references event_guests (id) on delete cascade,
    object_key text        not null,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);
create index guest_uploads_event_idx on guest_uploads (event_id);
create index guest_uploads_guest_idx on guest_uploads (guest_id);
