ALTER TABLE payment_attempts DROP CONSTRAINT IF EXISTS payment_attempts_status_check;
ALTER TABLE payment_attempts ADD CONSTRAINT payment_attempts_status_check
    CHECK (status IN ('PENDING','APPROVED','REJECTED','REFUNDED','CHARGED_BACK'));
