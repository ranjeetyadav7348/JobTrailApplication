-- One-time PostgreSQL setup for JobTrail.
-- Run as the postgres superuser:
--     psql -U postgres -f setup-postgres.sql
--
-- Matches the defaults in src/main/resources/application.yml. Override there or
-- via DB_NAME / DB_USERNAME / DB_PASSWORD if you want different values.

CREATE DATABASE jobtrail;

CREATE USER jobtrail WITH PASSWORD 'jobtrail';

GRANT ALL PRIVILEGES ON DATABASE jobtrail TO jobtrail;

-- PostgreSQL 15+ removed the implicit write access to the public schema, so the
-- app user needs it granted explicitly or Hibernate cannot create its tables.
\connect jobtrail

GRANT ALL ON SCHEMA public TO jobtrail;

ALTER SCHEMA public OWNER TO jobtrail;
