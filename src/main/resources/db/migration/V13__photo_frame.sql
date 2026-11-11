-- How the photos look on the photographer's page: none, thin, polaroid, mat, classic or glow.
ALTER TABLE profiles ADD COLUMN photo_frame text NOT NULL DEFAULT 'none';
