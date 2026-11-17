CREATE TABLE quote_items (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL REFERENCES quotes(id),
    position INTEGER NOT NULL,
    item_type VARCHAR(16) NOT NULL CHECK (item_type IN ('PART','SERVICE')),
    reference_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(15,2) NOT NULL CHECK (unit_price >= 0),
    UNIQUE (quote_id, position)
);
