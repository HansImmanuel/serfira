-- =====================================================================================
-- Serfira Core — V8: harden the seeded SYSTEM principal (non-interactive job actor only)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * 04_GAPS_ADDENDUM.md 3.3 (jobs run as the seeded non-interactive SYSTEM user) and
--     3.4 (SYSTEM only ever appears as "internal job" in the endpoint-to-role matrix),
--   * ADR-005 (authentication arrives with F2/F3 in Sprint 6b),
--   * .clinerules/60-security (default to deny; never ship a credential that can be used).
--
-- Decisions encoded here:
--   1. V1 seeded `password_hash = '{noop}!'`. `{noop}` is Spring Security's
--      DelegatingPasswordEncoder id for PLAINTEXT comparison, not an opaque sentinel: once F2
--      wires password login, the literal password `!` would match the SYSTEM row. The hash is
--      therefore replaced with `{disabled}`, an encoder id that is not registered, so
--      verification can only fail (fail-closed) even for a flow that ignores `is_active`.
--   2. `is_active = FALSE` is the authoritative guard: a login flow must refuse inactive users
--      (F2). The principal still exists as an FK target for `created_by`/`updated_by`
--      (Addendum 3.3) and is used in-process by jobs through AuditContext, so the row must stay.
--   3. `ck_app_user_system_inactive` states the invariant the seed only implied: the SYSTEM role
--      is never an interactive principal. Re-enabling it now needs a deliberate forward-only
--      migration instead of a data edit.
-- =====================================================================================

UPDATE app_user
   SET password_hash = '{disabled}',
       is_active     = FALSE,
       updated_at    = clock_timestamp(),
       -- Bootstrap migration: no interactive actor exists to attribute this write to.
       updated_by    = NULL
 WHERE username = 'SYSTEM';

COMMENT ON COLUMN app_user.password_hash IS
    'Argon2id hash for interactive users. Non-interactive rows (role SYSTEM) carry an encoder id '
    'that is not registered, so verification always fails (V8).';

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_system_inactive CHECK (role <> 'SYSTEM' OR is_active = FALSE);