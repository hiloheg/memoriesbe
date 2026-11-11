CREATE TABLE social_links (
    id         uuid PRIMARY KEY,
    owner_sub  text    NOT NULL,
    label      text    NOT NULL,
    url        text    NOT NULL,
    position   integer NOT NULL
);
CREATE INDEX social_links_owner_idx ON social_links (owner_sub, position);

ALTER TABLE profiles ADD COLUMN follow_gate boolean NOT NULL DEFAULT true;
