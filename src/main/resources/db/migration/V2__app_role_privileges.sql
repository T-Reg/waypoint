-- The app role (DML only) may never rewrite history; the trigger in V1 enforces this for every role too.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appRole}') THEN
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON routes, tenants, config_revision, key_rotation, gateway_keys TO %I', '${appRole}');
        EXECUTE format('GRANT SELECT, INSERT ON config_audit TO %I', '${appRole}');
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON config_audit FROM %I', '${appRole}');
        EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE config_audit_id_seq TO %I', '${appRole}');
    END IF;
END
$$;
