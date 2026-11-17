CREATE TABLE mercadopago_notifications (
    request_id VARCHAR(200) PRIMARY KEY,
    resource_id VARCHAR(200) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ix_mercadopago_notifications_pending ON mercadopago_notifications (received_at) WHERE processed_at IS NULL;
