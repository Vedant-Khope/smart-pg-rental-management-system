-- =============================================================================
-- V4__create_refresh_tokens.sql
-- Smart PG & Rental Management System
-- =============================================================================
-- Purpose  : Create the `refresh_tokens` table — persists issued JWT refresh
--            tokens to enable stateful session management.
-- Depends  : V1__create_users.sql (FK → users.id)
-- Author   : Smart PG Dev Team
-- Notes    :
--   • Many-to-one with users: one user can have MULTIPLE active sessions
--     (phone, laptop, tablet — each gets its own refresh token row).
--   • token column: UNIQUE + NOT NULL. Stores an opaque UUID string (not JWT).
--     The access token (JWT) is NOT stored here — it's stateless & short-lived.
--   • revoked flag: set to TRUE on logout or forced revocation.
--     We KEEP revoked rows (don't delete) for audit purposes.
--     A scheduled cleanup job purges old expired+revoked rows.
--   • ON DELETE CASCADE: if a user is hard-deleted (never in practice — soft-delete
--     only), their tokens are removed too. Prevents orphan token rows.
--   • NO ON DELETE CASCADE from the application's perspective — we use soft-delete.
--     The FK still has CASCADE as a DB-level safety net.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Create the `refresh_tokens` table
-- -----------------------------------------------------------------------------

CREATE TABLE refresh_tokens (

    -- -------------------------------------------------------------------------
    -- Primary Key
    -- -------------------------------------------------------------------------
    id          UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Foreign Key → users(id)
    -- ManyToOne: one user → many refresh token rows (multi-device support).
    -- NOT NULL: every token row MUST belong to a real user.
    -- NO UNIQUE on user_id — this is intentionally Many (tokens) to One (user).
    -- -------------------------------------------------------------------------
    user_id     UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Token Value
    -- The opaque token string sent to the client (stored in localStorage / cookie).
    -- This is a randomly generated UUID string (e.g., UUID.randomUUID().toString()).
    -- It is NOT a JWT — it has no payload, no signature, no claims.
    -- The only way to "decode" it is to look it up in THIS table.
    --
    -- UNIQUE: prevents a (hypothetically) duplicated UUID from being accepted twice.
    -- NOT NULL: can't create a token row without an actual token value.
    -- LENGTH 500: future-proofing if we migrate to signed opaque tokens (PASETO).
    -- -------------------------------------------------------------------------
    token       VARCHAR(500)    NOT NULL,

    -- -------------------------------------------------------------------------
    -- Expiry & Revocation
    -- expires_at: UTC instant after which this token is invalid.
    --   Default TTL: 30 days (set by AuthService, configurable via application.properties).
    --   NOT NULL: every token MUST have an expiry — tokens that live forever are a
    --   critical security vulnerability.
    -- revoked: TRUE = this token has been explicitly killed before natural expiry.
    --   Scenarios for revocation:
    --     • User clicks "Logout" → only THIS token row revoked
    --     • User changes password → ALL their token rows revoked (force re-login everywhere)
    --     • Admin suspends account → ALL their token rows revoked
    --   DEFAULT FALSE: a freshly issued token starts as non-revoked.
    -- -------------------------------------------------------------------------
    expires_at  TIMESTAMPTZ     NOT NULL,
    revoked     BOOLEAN         NOT NULL    DEFAULT FALSE,

    -- -------------------------------------------------------------------------
    -- Session Context (for security UI & audit)
    -- device_info: Parsed from the User-Agent header at login time.
    --   Examples: "Chrome 124 on macOS", "iPhone 15 Safari", "Postman/11.0"
    --   Shown in "Manage Active Sessions" UI so users can identify each device.
    --   Nullable: some clients (e.g., custom mobile apps) may not send User-Agent.
    -- ip_address: The IPv4 or IPv6 address of the client that obtained this token.
    --   Used in the security dashboard to flag logins from unusual locations.
    --   LENGTH 45: accommodates full IPv6 address (39 chars max) with padding.
    --   Example: "2001:0db8:85a3:0000:0000:8a2e:0370:7334"
    -- -------------------------------------------------------------------------
    device_info VARCHAR(500),
    ip_address  VARCHAR(45),

    -- -------------------------------------------------------------------------
    -- Audit Timestamp (created only — no updated_at because tokens are immutable)
    -- created_at: UTC instant when this token was first issued.
    --   Set once in @PrePersist. NEVER modified (updatable = false in JPA).
    -- -------------------------------------------------------------------------
    created_at  TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),

    -- -------------------------------------------------------------------------
    -- Constraints
    -- -------------------------------------------------------------------------
    CONSTRAINT pk_refresh_tokens            PRIMARY KEY (id),

    -- Token string must be globally unique (prevents replay attacks with collisions)
    CONSTRAINT uk_refresh_tokens_token      UNIQUE (token),

    -- Referential integrity
    CONSTRAINT fk_refresh_tokens_user_id
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE,

    -- Business rule: expiry must always be in the future relative to creation.
    -- This prevents inserting already-expired tokens via a bug or direct SQL.
    CONSTRAINT chk_refresh_tokens_expires_after_created
        CHECK (expires_at > created_at)

);

-- -----------------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------------

-- idx_refresh_tokens_token:
--   THE most critical index on this table.
--   Every POST /api/v1/auth/refresh-token call does:
--     SELECT * FROM refresh_tokens WHERE token = ?
--   Without this index: full table scan on every token refresh → O(n) where n
--     = total sessions ever created. At 10,000 active users, 30 logins each = 300,000 rows.
--   With this index : O(log n) B-Tree lookup — constant-time regardless of table size.
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens (token);

-- idx_refresh_tokens_user_id:
--   Used when revoking ALL sessions for a user.
--   Query: UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = ?
--   Triggered by: password change, password reset, admin suspension.
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- idx_refresh_tokens_expires_at:
--   Used by the cleanup job:
--     DELETE FROM refresh_tokens WHERE expires_at < NOW() AND revoked = TRUE
--   Without: full table scan for cleanup. With: index range scan → fast.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- Composite index: find active (non-expired, non-revoked) tokens for a user.
-- Used in the "Manage Sessions" UI: show all active devices for this user.
-- Query: SELECT * FROM refresh_tokens
--        WHERE user_id = ? AND revoked = FALSE AND expires_at > NOW()
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens (user_id, revoked, expires_at);

-- -----------------------------------------------------------------------------
-- Comments
-- -----------------------------------------------------------------------------

COMMENT ON TABLE  refresh_tokens             IS 'Persisted refresh tokens for stateful session management. Many-to-one with users.';
COMMENT ON COLUMN refresh_tokens.id          IS 'UUID PK.';
COMMENT ON COLUMN refresh_tokens.user_id     IS 'FK to users(id). Many tokens per user = multi-device support.';
COMMENT ON COLUMN refresh_tokens.token       IS 'Opaque UUID string token. NOT a JWT. Globally unique.';
COMMENT ON COLUMN refresh_tokens.expires_at  IS 'UTC expiry time. Token is invalid after this instant.';
COMMENT ON COLUMN refresh_tokens.revoked     IS 'TRUE = token has been explicitly killed before natural expiry. Never deleted, only revoked.';
COMMENT ON COLUMN refresh_tokens.device_info IS 'Parsed User-Agent string. Used in session management UI.';
COMMENT ON COLUMN refresh_tokens.ip_address  IS 'Client IP (IPv4 or IPv6). Max 45 chars for full IPv6.';
COMMENT ON COLUMN refresh_tokens.created_at  IS 'UTC timestamp of token issuance. Immutable.';
