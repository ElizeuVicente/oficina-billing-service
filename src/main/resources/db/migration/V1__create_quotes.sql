CREATE TABLE quotes (
    id UUID PRIMARY KEY,
    branch_id UUID NOT NULL,
    order_id UUID NOT NULL,
    amount NUMERIC(15,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(255) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_quote_branch_order UNIQUE (branch_id, order_id)
);
