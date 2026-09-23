-- =============================================================================
-- V5__create_user_audit_logs.sql
-- Smart PG & Rental Management System
-- =============================================================================
-- Purpose  : Create the `user_audit_logs` table — immutable, append-only record
--            of security-sensitive events on user accounts.
-- Depends  : V1__create_users.sql (FK → users.id, WITHOUT CASCADE)
-- Author   : Smart PG Dev Team
-- Notes    :
--   • APPEND-ONLY: This table must NEVER have UPDATE or DELETE issued against it.
--     Enforced by:
--       (a) No setters on the Java entity (only @Getter, no @Setter, no @PreUpdate).
--       (b) DB-level: REVOKE UPDATE, DELETE privileges from the application user
--           (done at the bottom of this script via a COMMENT noting the intent —
--            run manually or via a separate DBA-level script).
--       (c) All writes go through UserAuditLogRepository.save() which only INSERTs.
--   • details: stored as JSONB (PostgreSQL native binary JSON) for:
--       (a) Efficient GIN-indexed querying on nested keys.
--       (b) Flexible per-event payload without schema changes.
--       (c) Compression and binary storage for speed.
--   • FK WITHOUT CASCADE: if a user is soft-deleted, their audit logs stay.
--     Audit logs are a compliance record — they MUST outlive the user account.
--     Hard deletes on users are forbidden anyway (soft-delete design).
--   • Composite index: critical for the admin forensics queries that filter
--     by user + action + time range simultaneously.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Create the `user_audit_logs` table
-- -----------------------------------------------------------------------------

CREATE TABLE user_audit_logs (

    -- -------------------------------------------------------------------------
    -- Primary Key
    -- -------------------------------------------------------------------------
    id          UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- WHO: Which user's account was affected by this event?
    --
    -- ManyToOne: one user → many log entries (a user logs in many times).
    -- NOT NULL: every log entry MUST reference a user.
    --
    -- CRITICAL — NO ON DELETE CASCADE:
    --   If this was CASCADE, physically deleting a user would destroy their
    --   entire audit history — which violates compliance requirements.
    --   We use ON DELETE RESTRICT instead, meaning a physical user delete
    --   would FAIL if the user has any audit log rows. This is an extra safety
    --   guard: you literally CAN'T hard-delete a user with audit history.
    --   (We never hard-delete anyway, but RESTRICT makes the DB enforce it.)
    -- -------------------------------------------------------------------------
    user_id     UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- WHAT: What type of event occurred?
    -- action: The event type — stored as VARCHAR with CHECK constraint.
    --   Maps 1:1 with UserAuditLog.AuditAction enum in Java.
    --   Valid values: LOGIN, FAILED_LOGIN, LOGOUT, PASSWORD_CHANGE,
    --                 PASSWORD_RESET, ROLE_CHANGE, STATUS_CHANGE,
    --                 PROFILE_UPDATE, TOKEN_REFRESH, VERIFICATION
    -- status: Was it successful or did it fail?
    --   Maps 1:1 with UserAuditLog.AuditStatus enum.
    --   Valid values: SUCCESS, FAILURE
    -- -------------------------------------------------------------------------
    action      VARCHAR(30)     NOT NULL,
    status      VARCHAR(10)     NOT NULL,

    -- -------------------------------------------------------------------------
    -- CONTEXT: Event-specific metadata stored as PostgreSQL JSONB.
    -- Why JSONB over TEXT / JSON?
    --   • JSONB: binary storage — faster reads and GIN index support.
    --   • JSON : stored as-is — no indexing, slower key lookups.
    --   • TEXT : no type safety, no JSON operators like ->> or @>.
    --
    -- Example payloads:
    --   LOGIN success      : {"deviceType": "mobile"}
    --   FAILED_LOGIN       : {"reason": "BAD_PASSWORD", "attemptCount": 3}
    --   ROLE_CHANGE        : {"oldRole": "TENANT", "newRole": "ADMIN", "changedBy": "uuid-..."}
    --   STATUS_CHANGE      : {"oldStatus": "ACTIVE", "newStatus": "SUSPENDED", "reason": "Fraud report"}
    --   PASSWORD_RESET     : {"method": "EMAIL_LINK"}
    --   TOKEN_REFRESH      : {"newTokenIssuedAt": "2025-01-01T12:00:00Z"}
    --
    -- Nullable: not all events need extra context (e.g., a simple LOGOUT).
    -- -------------------------------------------------------------------------
    details     JSONB,

    -- -------------------------------------------------------------------------
    -- WHERE: From what network location did this event happen?
    -- ip_address: IPv4 or IPv6 of the client. Length 45 = max IPv6 length.
    --   Used for geo-anomaly detection: "login from India, then 5 min later from Russia?"
    --   Nullable: internal service calls may not have a client IP.
    -- user_agent: Raw User-Agent header from the HTTP request.
    --   Used to distinguish browser from bot, or known client from suspicious one.
    --   Nullable: same reason as ip_address.
    -- -------------------------------------------------------------------------
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(500),

    -- -------------------------------------------------------------------------
    -- WHEN: Precise UTC timestamp of this event.
    -- NOT NULL: every audit entry MUST have a timestamp.
    -- DEFAULT NOW(): even if the Java code somehow doesn't set it, DB fills it in.
    -- updatable = false in JPA mirrors the immutability of this column.
    -- This is the primary axis for time-range forensics queries.
    -- -------------------------------------------------------------------------
    timestamp   TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),

    -- -------------------------------------------------------------------------
    -- Constraints
    -- -------------------------------------------------------------------------
    CONSTRAINT pk_user_audit_logs  PRIMARY KEY (id),

    -- Referential integrity WITHOUT cascade (audit logs outlive user accounts)
    CONSTRAINT fk_user_audit_logs_user_id
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE RESTRICT,     -- <-- RESTRICT, not CASCADE. Intentional.

    -- Enum validation for action
    CONSTRAINT chk_user_audit_logs_action
        CHECK (action IN (
            'LOGIN', 'FAILED_LOGIN', 'LOGOUT',
            'PASSWORD_CHANGE', 'PASSWORD_RESET',
            'ROLE_CHANGE', 'STATUS_CHANGE',
            'PROFILE_UPDATE', 'TOKEN_REFRESH', 'VERIFICATION'
        )),

    -- Enum validation for status
    CONSTRAINT chk_user_audit_logs_status
        CHECK (status IN ('SUCCESS', 'FAILURE'))

);

-- -----------------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------------

-- idx_user_audit_logs_user_id:
--   THE primary query: "Show me all audit events for user X."
--   Used in: GET /api/v1/admin/users/{id}/audit-logs
--   Without: Full table scan on potentially millions of rows.
--   With   : Direct B-Tree lookup by user_id.
CREATE INDEX idx_user_audit_logs_user_id ON user_audit_logs (user_id);

-- idx_user_audit_logs_action:
--   Admin investigation: "Show me all FAILED_LOGIN events in the last hour."
--   Useful for detecting brute-force attack patterns system-wide.
CREATE INDEX idx_user_audit_logs_action ON user_audit_logs (action);

-- idx_user_audit_logs_timestamp:
--   Retention/archival: "Find all rows older than 1 year."
--   Scheduled job: DELETE FROM user_audit_logs WHERE timestamp < NOW() - INTERVAL '1 year'
--   (or INSERT INTO audit_archive SELECT ... DELETE FROM user_audit_logs WHERE ...)
CREATE INDEX idx_user_audit_logs_timestamp ON user_audit_logs (timestamp);

-- idx_user_audit_logs_user_action_ts (COMPOSITE — most important for forensics):
--   "Show me all FAILED_LOGIN events for user X between 2025-01-01 and 2025-01-15."
--   This is the single most common forensic/admin query and needs ALL three columns indexed.
--   PostgreSQL uses this index for queries filtering on user_id + action + timestamp range.
--   Column order: user_id first (highest selectivity) → action → timestamp.
CREATE INDEX idx_user_audit_logs_user_action_ts
    ON user_audit_logs (user_id, action, timestamp DESC);

-- GIN index on details JSONB:
--   Enables queries like: SELECT * FROM user_audit_logs WHERE details @> '{"reason": "BAD_PASSWORD"}'
--   GIN = Generalized Inverted Index — designed for container types (JSONB, arrays, tsvector).
--   Use case: "Find all failed logins with BAD_PASSWORD reason across all users."
CREATE INDEX idx_user_audit_logs_details_gin
    ON user_audit_logs USING GIN (details);

-- -----------------------------------------------------------------------------
-- IMMUTABILITY ENFORCEMENT (Read This!)
-- -----------------------------------------------------------------------------
-- The following REVOKE statements remove UPDATE and DELETE privileges from the
-- application database user, enforcing the append-only contract at the DB level.
--
-- IMPORTANT: Replace 'smartpg_app_user' with your actual PostgreSQL application
-- username (the one used in application.properties → spring.datasource.username).
--
-- Run this SEPARATELY as a DBA/superuser after the table is created.
-- Flyway runs as a migration user who may not have GRANT/REVOKE privileges.
-- Uncomment and run manually, or put in a separate DBA-controlled script:
--
--   REVOKE UPDATE, DELETE ON user_audit_logs FROM smartpg_app_user;
--   GRANT  SELECT, INSERT          ON user_audit_logs TO   smartpg_app_user;
--
-- For local dev, this is optional. For production, this is MANDATORY.
-- -----------------------------------------------------------------------------


-- -----------------------------------------------------------------------------
-- Comments
-- -----------------------------------------------------------------------------

COMMENT ON TABLE  user_audit_logs            IS 'Append-only audit trail of security-sensitive user account events. NEVER UPDATE or DELETE rows here.';
COMMENT ON COLUMN user_audit_logs.id         IS 'UUID PK.';
COMMENT ON COLUMN user_audit_logs.user_id    IS 'FK to users(id). ON DELETE RESTRICT — audit logs outlive deleted accounts.';
COMMENT ON COLUMN user_audit_logs.action     IS 'Event type: LOGIN | FAILED_LOGIN | LOGOUT | PASSWORD_CHANGE | PASSWORD_RESET | ROLE_CHANGE | STATUS_CHANGE | PROFILE_UPDATE | TOKEN_REFRESH | VERIFICATION';
COMMENT ON COLUMN user_audit_logs.status     IS 'Outcome: SUCCESS | FAILURE';
COMMENT ON COLUMN user_audit_logs.details    IS 'JSONB payload with event-specific metadata. GIN-indexed for key-based queries.';
COMMENT ON COLUMN user_audit_logs.ip_address IS 'Client IPv4/IPv6 address. Max 45 chars for full IPv6.';
COMMENT ON COLUMN user_audit_logs.user_agent IS 'Raw User-Agent header string from the HTTP request.';
COMMENT ON COLUMN user_audit_logs.timestamp  IS 'UTC timestamp of the event. Immutable. Primary axis for time-range forensics.';
