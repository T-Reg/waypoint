#!/bin/bash
# Two roles: the migrator owns the schema (DDL, used only by Flyway); the app role gets DML only.
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE waypoint_migrator LOGIN PASSWORD '${MIGRATOR_DB_PASSWORD}';
    CREATE ROLE waypoint_app LOGIN PASSWORD '${APP_DB_PASSWORD}';
    ALTER DATABASE waypoint OWNER TO waypoint_migrator;
    ALTER SCHEMA public OWNER TO waypoint_migrator;
    REVOKE ALL ON DATABASE waypoint FROM PUBLIC;
    GRANT CONNECT ON DATABASE waypoint TO waypoint_app, waypoint_migrator;
    GRANT USAGE ON SCHEMA public TO waypoint_app;
EOSQL
