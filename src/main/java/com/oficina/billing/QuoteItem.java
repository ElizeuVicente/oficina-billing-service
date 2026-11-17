package com.oficina.billing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "quote_items")
class QuoteItem {
    @Id private UUID id;
    @Column(name = "quote_id", nullable = false, updatable = false) private UUID quoteId;
    @Column(nullable = false) private int position;
    @Column(name = "item_type", nullable = false, length = 16) private String itemType;
    @Column(name = "reference_id", nullable = false) private UUID referenceId;
    @Column(nullable = false) private int quantity;
    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2) private BigDecimal unitPrice;
    protected QuoteItem() { }
    QuoteItem(UUID quoteId, int position, QuoteItemsController.Item item) {
        this.id = UUID.randomUUID(); this.quoteId = quoteId; this.position = position;
        this.itemType = item.type(); this.referenceId = item.referenceId(); this.quantity = item.quantity(); this.unitPrice = item.unitPrice();
    }
    boolean matches(QuoteItemsController.Item item) {
        return itemType.equals(item.type()) && referenceId.equals(item.referenceId()) && quantity == item.quantity()
                && unitPrice.compareTo(item.unitPrice()) == 0;
    }
    public String getItemType() { return itemType; }
    public UUID getReferenceId() { return referenceId; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
