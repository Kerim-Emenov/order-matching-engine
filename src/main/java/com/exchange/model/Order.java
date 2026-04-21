package com.exchange.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable representation of an order submitted to the exchange.
 *
 * For MARKET orders, price is null — they consume liquidity at whatever the best available price is.
 * For LIMIT orders, price is required — they sit in the book until matched or cancelled.
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class Order {

    public enum Side { BUY, SELL }
    public enum Type { LIMIT, MARKET }
    public enum Status { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED }

    private final String orderId;
    private final String symbol;        // e.g. "BTC-USD", "AAPL"
    private final Side side;
    private final Type type;
    private final BigDecimal price;     // null for MARKET orders
    private final BigDecimal quantity;
    private final BigDecimal filledQuantity;
    private final Status status;
    private final Instant createdAt;

    @JsonCreator
    public Order(
            @JsonProperty("orderId") String orderId,
            @JsonProperty("symbol") String symbol,
            @JsonProperty("side") Side side,
            @JsonProperty("type") Type type,
            @JsonProperty("price") BigDecimal price,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("filledQuantity") BigDecimal filledQuantity,
            @JsonProperty("status") Status status,
            @JsonProperty("createdAt") Instant createdAt) {
        this.orderId = orderId != null ? orderId : UUID.randomUUID().toString();
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.filledQuantity = filledQuantity != null ? filledQuantity : BigDecimal.ZERO;
        this.status = status != null ? status : Status.OPEN;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public BigDecimal getRemainingQuantity() {
        return quantity.subtract(filledQuantity);
    }

    public boolean isFilled() {
        return filledQuantity.compareTo(quantity) >= 0;
    }
}
