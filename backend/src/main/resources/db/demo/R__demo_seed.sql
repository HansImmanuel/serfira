-- =====================================================================================
-- Serfira Core — V99: demo login users (DEMO PROFILE ONLY)
-- -------------------------------------------------------------------------------------
-- Source of truth: 04_GAPS_ADDENDUM.md §18.3 (demo users) and §18.4 (profile-gated seed).
--
-- This migration is NOT on the default classpath location (db/migration). It lives in
-- db/demo and is loaded only when the `demo` Spring profile is active, because
-- application-demo.properties adds `classpath:db/demo` to spring.flyway.locations.
-- The default and production profiles never see it, so a production database stays free
-- of demo credentials.
--
-- Passwords: Argon2id hashes, generated with Spring Security's
-- Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()
-- (m=16384 KiB, t=2, p=1, 16-byte salt, 32-byte hash). The plaintext demo credentials are
-- documented only in Addendum §18.3 and are for the portfolio demo, never a real account.
--
-- IDs are fixed so the demo script and documentation can refer to them reliably.
-- created_by/updated_by stay NULL: this is a bootstrap seed (Addendum §3.3).
-- ON CONFLICT makes the seed harmless if the row already exists.
-- =====================================================================================

INSERT INTO app_user (
    id, username, password_hash, full_name, role, is_active,
    failed_login_attempts, locked_until, last_login_at,
    created_at, created_by, updated_at, updated_by)
VALUES
    ('00000000-0000-0000-0000-000000000010',
     'admin',
     '$argon2id$v=19$m=16384,t=2,p=1$T72Co4iWN4YanJf5ZNwgoA$FfqJPk66YZ52g0OYJfgyPwNxoazJito/M0u9mf6tKlE',
     'Administrator (demo)', 'ADMIN_OPERASIONAL', TRUE, 0, NULL, NULL,
     clock_timestamp(), NULL, clock_timestamp(), NULL),
    ('00000000-0000-0000-0000-000000000011',
     'finance',
     '$argon2id$v=19$m=16384,t=2,p=1$acVMPwKZMfeIf+vE3v1fCg$C8reii1iD+ZqQD8hDZT2t8wUUaZ16IJ4HSDteQBu6qU',
     'Finance (demo)', 'FINANCE', TRUE, 0, NULL, NULL,
     clock_timestamp(), NULL, clock_timestamp(), NULL),
    ('00000000-0000-0000-0000-000000000012',
     'manager',
     '$argon2id$v=19$m=16384,t=2,p=1$tC69cKTf7fLt+J67HckaPA$5X5eGsZhKJkqy50wBItIZ+9pwtVJl0f0VYDQUwAwkqs',
     'Manager (demo)', 'MANAJEMEN', TRUE, 0, NULL, NULL,
     clock_timestamp(), NULL, clock_timestamp(), NULL)
ON CONFLICT (username) DO NOTHING;
