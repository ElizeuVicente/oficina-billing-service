ALTER TABLE quotes ADD COLUMN saga_id UUID;
ALTER TABLE quotes ADD COLUMN correlation_id UUID;
UPDATE quotes SET saga_id = order_id, correlation_id = order_id;
ALTER TABLE quotes ALTER COLUMN saga_id SET NOT NULL;
ALTER TABLE quotes ALTER COLUMN correlation_id SET NOT NULL;
CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_outbox_aggregate_type UNIQUE (aggregate_id, event_type)
);
CREATE INDEX ix_outbox_pending ON outbox_events (published_at, occurred_at);
