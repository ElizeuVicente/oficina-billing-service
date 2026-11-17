ALTER TABLE quotes DROP CONSTRAINT IF EXISTS quotes_status_check;
ALTER TABLE quotes ADD CONSTRAINT quotes_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'));
