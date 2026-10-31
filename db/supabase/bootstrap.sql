-- DBA template: execute using an authorized Supabase database administrator.
-- Supply runtime_password and migration_password as psql variables securely.
-- Do not put real credentials in this file or commit them.
-- Supabase-managed roles (postgres, authenticator, anon, authenticated,
-- service_role and supabase_* roles) are intentionally untouched.
-- Run once for a new project; inspect existing roles before adapting a rerun.
CREATE ROLE ziper_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';
CREATE ROLE ziper_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'migration_password';
GRANT CONNECT ON DATABASE postgres TO ziper_app, ziper_migrator;
-- Provision the dedicated schema as DBA so the migrator needs no database-wide CREATE.
CREATE SCHEMA ziper AUTHORIZATION ziper_migrator;
REVOKE ALL ON SCHEMA ziper FROM PUBLIC;
GRANT USAGE ON SCHEMA ziper TO ziper_app;
-- V1 grants current/future object privileges as the actual object creator.
-- All application objects must be created by ziper_migrator through Flyway.
-- Audit inherited PUBLIC privileges separately under your project's DBA policy.
-- JDBC uses ziper_app and does not propagate an HTTP JWT into PostgreSQL RLS.
