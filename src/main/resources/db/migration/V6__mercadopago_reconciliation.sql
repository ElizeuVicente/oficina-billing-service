ALTER TABLE payment_attempts DROP CONSTRAINT IF EXISTS payment_attempts_status_check;
ALTER TABLE payment_attempts ADD CONSTRAINT payment_attempts_status_check CHECK (status IN ('PENDING','APPROVED','REJECTED'));
ALTER TABLE payment_attempts ADD COLUMN provider_payment_id VARCHAR(20) UNIQUE;
ALTER TABLE mercadopago_notifications ADD COLUMN last_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE mercadopago_notifications ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE mercadopago_notifications ADD COLUMN last_error VARCHAR(200);
