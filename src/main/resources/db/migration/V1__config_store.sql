CREATE TABLE routes (
    id          text        PRIMARY KEY,
    path_prefix text        NOT NULL UNIQUE,
    spec        jsonb       NOT NULL,
    version     bigint      NOT NULL,
    updated_at  timestamptz NOT NULL,
    updated_by  text        NOT NULL
);

CREATE TABLE tenants (
    id         text        PRIMARY KEY,
    spec       jsonb       NOT NULL,
    version    bigint      NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by text        NOT NULL
);

CREATE TABLE config_revision (
    id       smallint PRIMARY KEY CHECK (id = 1),
    revision bigint   NOT NULL
);
INSERT INTO config_revision (id, revision) VALUES (1, 0);

CREATE TABLE key_rotation (
    id         smallint PRIMARY KEY CHECK (id = 1),
    generation bigint   NOT NULL
);
INSERT INTO key_rotation (id, generation) VALUES (1, 0);

CREATE TABLE config_audit (
    id          bigserial   PRIMARY KEY,
    at          timestamptz NOT NULL,
    actor       text        NOT NULL,
    action      text        NOT NULL,
    entity_type text        NOT NULL,
    entity_id   text        NOT NULL,
    before      jsonb,
    after       jsonb,
    revision    bigint      NOT NULL
);

-- Append-only regardless of which DB user connects.
CREATE FUNCTION config_audit_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'config_audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER config_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON config_audit
    FOR EACH ROW EXECUTE FUNCTION config_audit_append_only();

CREATE TRIGGER config_audit_no_truncate
    BEFORE TRUNCATE ON config_audit
    FOR EACH STATEMENT EXECUTE FUNCTION config_audit_append_only();

CREATE TABLE gateway_keys (
    kid            text        PRIMARY KEY,
    public_jwk     text        NOT NULL,
    instance_id    text        NOT NULL,
    created_at     timestamptz NOT NULL,
    last_heartbeat timestamptz NOT NULL,
    retired_at     timestamptz
);
CREATE INDEX gateway_keys_instance ON gateway_keys (instance_id);
