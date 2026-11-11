-- The frame around the whole photographer page content: none, card, outline, double or mat.
ALTER TABLE profiles ADD COLUMN page_frame text NOT NULL DEFAULT 'none';
