-- Face search: the photographer switches it on per event; guests then find their photos by scanning their face.
alter table events add column face_search_enabled boolean not null default false;

-- Which photos of an event were looked at. `version` is the photo's last-modified time (epoch seconds), so a replaced photo is read again.
-- A photo that keeps failing is given up on after 3 attempts (done = true with faces = 0).
create table event_face_photos (
    event_id   uuid    not null references events (id) on delete cascade,
    object_key text    not null,
    version    bigint  not null,
    faces      int     not null default 0,
    attempts   int     not null default 0,
    done       boolean not null default false,
    primary key (event_id, object_key)
);

-- One row per face found in an event photo: 128 floats (512 bytes). Not a name, not linked to any account.
create table event_faces (
    id         uuid primary key default gen_random_uuid(),
    event_id   uuid  not null references events (id) on delete cascade,
    object_key text  not null,
    embedding  bytea not null
);
create index event_faces_event_idx on event_faces (event_id);
