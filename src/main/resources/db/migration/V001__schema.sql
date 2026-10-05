-- Core schema for the revision store. See docs/ARCHITECTURE.md section 2.2.

CREATE TABLE game (
    id          smallint PRIMARY KEY,
    slug        text NOT NULL UNIQUE,
    name        text NOT NULL,
    environment text NOT NULL
);

CREATE TABLE entity_type (
    id            smallint PRIMARY KEY,
    game_id       smallint NOT NULL REFERENCES game(id),
    key           text NOT NULL,
    kind          text NOT NULL,
    display_name  text NOT NULL,
    gameval_group text,
    UNIQUE (game_id, key)
);

CREATE TABLE revision (
    game_id          smallint NOT NULL REFERENCES game(id),
    rev              integer NOT NULL,
    source           text NOT NULL DEFAULT 'openrs2',
    source_cache_id  integer,
    source_timestamp timestamptz,
    status           text NOT NULL DEFAULT 'DISCOVERED',
    stage            text,
    has_data         boolean NOT NULL DEFAULT false,
    published        boolean NOT NULL DEFAULT false,
    published_at     timestamptz,
    publish_stamp    bigint NOT NULL DEFAULT 0,
    attempts         integer NOT NULL DEFAULT 0,
    error            text,
    metrics          jsonb,
    raw_path         text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id, rev)
);

CREATE INDEX revision_published_idx ON revision (game_id, rev) WHERE published;

CREATE TABLE ingest_run (
    id          bigserial PRIMARY KEY,
    game_id     smallint NOT NULL,
    rev         integer NOT NULL,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    status      text NOT NULL,
    stage       text,
    error       text,
    metrics     jsonb
);

CREATE INDEX ingest_run_rev_idx ON ingest_run (game_id, rev, id DESC);

CREATE TABLE entity_payload (
    hash bytea PRIMARY KEY,
    body jsonb NOT NULL,
    size integer NOT NULL
);

CREATE TABLE entity_blob (
    hash  bytea PRIMARY KEY,
    bytes bytea NOT NULL,
    size  integer NOT NULL
);

CREATE TABLE entity_version (
    game_id       smallint NOT NULL,
    type_id       smallint NOT NULL,
    entity_id     integer NOT NULL,
    valid_from    integer NOT NULL,
    valid_to      integer,
    payload_hash  bytea NOT NULL,
    blob_hash     bytea,
    name          text,
    ingest_rev    integer NOT NULL,
    closed_by_rev integer,
    PRIMARY KEY (game_id, type_id, entity_id, valid_from)
) PARTITION BY LIST (game_id);

CREATE INDEX entity_version_from_idx ON entity_version (game_id, type_id, valid_from);
CREATE INDEX entity_version_to_idx ON entity_version (game_id, type_id, valid_to);
CREATE INDEX entity_version_current_idx ON entity_version (game_id, type_id, entity_id) WHERE valid_to IS NULL;

-- Reverse index: an entity version references another id (map region version -> object ids
-- placed in it, used by /map/objects/{id}). Rows live and die with their entity version.
CREATE TABLE entity_ref (
    game_id    smallint NOT NULL,
    type_id    smallint NOT NULL,
    entity_id  integer NOT NULL,
    valid_from integer NOT NULL,
    ref_id     integer NOT NULL,
    PRIMARY KEY (game_id, type_id, ref_id, entity_id, valid_from)
);

-- Per-revision side data that is not entity shaped (xtea keys, interface manifest).
CREATE TABLE revision_artifact (
    game_id smallint NOT NULL,
    rev     integer NOT NULL,
    kind    text NOT NULL,
    body    jsonb NOT NULL,
    PRIMARY KEY (game_id, rev, kind)
);

CREATE OR REPLACE FUNCTION ensure_game_partition(p_game smallint) RETURNS void AS $$
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS entity_version_g%s PARTITION OF entity_version FOR VALUES IN (%s)',
        p_game, p_game
    );
END
$$ LANGUAGE plpgsql;
