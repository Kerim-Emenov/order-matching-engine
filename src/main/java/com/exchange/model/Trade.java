package com.exchange.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Represents a completed trade between two orders.
 * Published to Kafka when a match occurs.
 */
@Getter
@ToString
@Builder
public class Trade {

    private final String tradeId;
    private final String symbol;
    private final String buyOrderId;
    private final String sellOrderId;
    private final BigDecimal price;     // execution price
    private final BigDecimal quantity;  // matched quantity
    private final Instant executedAt;

    public static Trade of(String symbol, Order buyOrder, Order sellOrder,
                           BigDecimal price, BigDecimal quantity) {
        return Trade.builder()
                .tradeId(UUID.randomUUID().toString())
                .symbol(symbol)
                .buyOrderId(buyOrder.getOrderId())
                .sellOrderId(sellOrder.getOrderId())
                .price(price)
                .quantity(quantity)
                .executedAt(Instant.now())
                .build();
    }
}
