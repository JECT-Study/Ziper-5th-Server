-- V1 granted all existing tables, including Flyway metadata, to the runtime role.
-- Preserve V1 checksums and business-object default privileges on existing installations.
REVOKE ALL PRIVILEGES ON TABLE ziper.flyway_schema_history FROM ziper_app;
