-- Camera FTP passwords are kept encrypted so the photographer can look them up again. Older cameras have none until reset.
alter table cameras add column password_enc text;
