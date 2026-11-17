CREATE TABLE checkout_preferences (
    attempt_id UUID PRIMARY KEY REFERENCES payment_attempts(attempt_id),
    state VARCHAR(20) NOT NULL CHECK (state IN ('DISPATCHING','READY','UNKNOWN')),
    preference_id VARCHAR(200),
    checkout_url TEXT,
    sandbox_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
