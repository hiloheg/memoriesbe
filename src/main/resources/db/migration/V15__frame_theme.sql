-- One frame theme now decorates the picture, header, tags and photos together (none, neon, gold, holo, pixel, paper).
-- Map the earlier photo-only choices onto the closest theme, and drop the separate page frame.
UPDATE profiles SET photo_frame = CASE photo_frame
    WHEN 'glow' THEN 'neon'
    WHEN 'classic' THEN 'gold'
    WHEN 'thin' THEN 'paper'
    WHEN 'polaroid' THEN 'paper'
    WHEN 'mat' THEN 'paper'
    ELSE photo_frame END;
ALTER TABLE profiles RENAME COLUMN photo_frame TO frame;
ALTER TABLE profiles DROP COLUMN page_frame;
