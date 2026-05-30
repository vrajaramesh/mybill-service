-- Run this once on any new database before starting the application.

-- ── pgvector (required for product visual search) ──────────────────────────
CREATE EXTENSION IF NOT EXISTS vector;

-- Creates the platform-level tables in the public schema.
-- Safe to re-run (idempotent via IF NOT EXISTS / ADD COLUMN IF NOT EXISTS).

-- ── Superadmin users (platform-level, not per-firm) ────────────────────────
CREATE TABLE IF NOT EXISTS public.app_users_public (
    user_id       SERIAL       PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(100),
    email         VARCHAR(100) UNIQUE,
    phone         VARCHAR(20),
    role          VARCHAR(20)  NOT NULL DEFAULT 'SUPERADMIN',
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP,
    last_login_at TIMESTAMP
);

-- ── Firms (one row per registered business) ────────────────────────────────
CREATE TABLE IF NOT EXISTS public.firms (
    firm_id       BIGSERIAL    PRIMARY KEY,
    firm_name     VARCHAR(200) NOT NULL,
    firm_code     VARCHAR(50)  NOT NULL UNIQUE,
    schema_name   VARCHAR(100) NOT NULL UNIQUE,
    owner_email   VARCHAR(100),
    superadmin_id INTEGER      REFERENCES public.app_users_public(user_id),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP
);

-- Migrate existing deployments that may be missing these columns
ALTER TABLE public.firms ADD COLUMN IF NOT EXISTS owner_email   VARCHAR(100);
ALTER TABLE public.firms ADD COLUMN IF NOT EXISTS superadmin_id INTEGER REFERENCES public.app_users_public(user_id);

-- ── User–firm access (cross-firm admin relationships) ─────────────────────
-- user_id here refers to a user_id in a firm's own app_users table (per-schema).
CREATE TABLE IF NOT EXISTS public.user_firm_access (
    access_id    SERIAL      PRIMARY KEY,
    user_id      INTEGER     NOT NULL,
    firm_id      INTEGER     NOT NULL REFERENCES public.firms(firm_id),
    firm_code    VARCHAR(50) NOT NULL,
    access_level VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    is_primary   BOOLEAN     NOT NULL DEFAULT FALSE,
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    assigned_by  VARCHAR(100),
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP
);

-- ── Admin–firm access (multi-firm admins stored in public schema) ─────────────
-- admin_id references app_users_public.user_id (role = 'ADMIN')
CREATE TABLE IF NOT EXISTS public.admin_firm_access (
    id          SERIAL      PRIMARY KEY,
    admin_id    INTEGER     NOT NULL REFERENCES public.app_users_public(user_id) ON DELETE CASCADE,
    firm_id     BIGINT      NOT NULL REFERENCES public.firms(firm_id) ON DELETE CASCADE,
    firm_code   VARCHAR(50) NOT NULL,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    assigned_by VARCHAR(100),
    created_at  TIMESTAMP   DEFAULT NOW(),
    UNIQUE(admin_id, firm_id)
);

CREATE INDEX IF NOT EXISTS idx_afa_admin_id ON public.admin_firm_access(admin_id);
CREATE INDEX IF NOT EXISTS idx_afa_firm_id  ON public.admin_firm_access(firm_id);
