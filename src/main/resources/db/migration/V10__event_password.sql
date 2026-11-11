-- Protected events: listed on the page, but the photos need a password (kept encrypted so the photographer can see it again).
alter table events add column access_password_enc text;
