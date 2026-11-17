CREATE TABLE payment_attempts (
    attempt_id UUID PRIMARY KEY,
    quote_id UUID NOT NULL UNIQUE REFERENCES quotes(id),
    amount NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
