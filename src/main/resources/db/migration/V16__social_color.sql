-- Color of the follow (social) buttons on the photographer page. Empty means: use the page accent.
ALTER TABLE profiles ADD COLUMN social_color text NOT NULL DEFAULT '';
