package com.exchange.orderbook;

import com.exchange.model.Order;
import com.exchange.model.Trade;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Order Book for a single trading symbol.
 *
 * Data structure:
 *   bids (BUY side):  sorted DESCENDING by price — highest bidder gets matched first
 *   asks (SELL side): sorted ASCENDING by price  — cheapest seller gets matched first
 *
 * Each price level holds a FIFO queue of orders (time priority).
 *
 * This is NOT thread-safe by itself — callers must synchronize or use OrderBookManager.
 */
public class OrderBook {

    private final String symbol;

    // Price → queue of orders at that price level (FIFO)
    // TreeMap with reversed comparator = bids sorted high→low
    private final TreeMap<BigDecimal, Deque<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());

    // TreeMap with natural order = asks sorted low→high
    private final TreeMap<BigDecimal, Deque<Order>> asks =
            new TreeMap<>();

    // Fast lookup: orderId → order (for cancellations)
    private final Map<String, Order> orderIndex = new ConcurrentHashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    /**
     * Add a limit order to the book (no matching, just insertion).
     */
    public void addLimitOrder(Order order) {
        TreeMap<BigDecimal, Deque<Order>> side = order.getSide() == Order.Side.BUY ? bids : asks;
        side.computeIfAbsent(order.getPrice(), k -> new ArrayDeque<>()).addLast(order);
        orderIndex.put(order.getOrderId(), order);
    }

    /**
     * Remove an order from the book (cancellation or after fill update).
     */
    public void removeOrder(Order order) {
        TreeMap<BigDecimal, Deque<Order>> side = order.getSide() == Order.Side.BUY ? bids : asks;
        Deque<Order> level = side.get(order.getPrice());
        if (level != null) {
            level.removeIf(o -> o.getOrderId().equals(order.getOrderId()));
            if (level.isEmpty()) {
                side.remove(order.getPrice());
            }
        }
        orderIndex.remove(order.getOrderId());
    }

    /**
     * Replace an existing order in the book (used after partial fill to update state).
     */
    public void replaceOrder(Order oldOrder, Order newOrder) {
        TreeMap<BigDecimal, Deque<Order>> side = oldOrder.getSide() == Order.Side.BUY ? bids : asks;
        Deque<Order> level = side.get(oldOrder.getPrice());
        if (level != null) {
            // Replace in-place to preserve queue position (time priority)
            ListIterator<Order> it = ((ArrayDeque<Order>) level).stream()
                    .collect(java.util.stream.Collectors.toCollection(ArrayDeque::new))
                    .iterator();
            // Simpler: rebuild the deque swapping the one element
            Deque<Order> updated = new ArrayDeque<>();
            for (Order o : level) {
                updated.addLast(o.getOrderId().equals(oldOrder.getOrderId()) ? newOrder : o);
            }
            level.clear();
            level.addAll(updated);
        }
        orderIndex.put(newOrder.getOrderId(), newOrder);
    }

    public Optional<Map.Entry<BigDecimal, Deque<Order>>> bestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstEntry());
    }

    public Optional<Map.Entry<BigDecimal, Deque<Order>>> bestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstEntry());
    }

    public Optional<Order> findOrder(String orderId) {
        return Optional.ofNullable(orderIndex.get(orderId));
    }

    public TreeMap<BigDecimal, Deque<Order>> getBids() { return bids; }
    public TreeMap<BigDecimal, Deque<Order>> getAsks() { return asks; }
    public String getSymbol() { return symbol; }

    /**
     * Snapshot of the order book for API response.
     * Returns top N levels for each side.
     */
    public OrderBookSnapshot snapshot(int depth) {
        List<PriceLevel> bidLevels = new ArrayList<>();
        List<PriceLevel> askLevels = new ArrayList<>();

        int count = 0;
        for (Map.Entry<BigDecimal, Deque<Order>> e : bids.entrySet()) {
            if (count++ >= depth) break;
            BigDecimal totalQty = e.getValue().stream()
                    .map(Order::getRemainingQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            bidLevels.add(new PriceLevel(e.getKey(), totalQty, e.getValue().size()));
        }

        count = 0;
        for (Map.Entry<BigDecimal, Deque<Order>> e : asks.entrySet()) {
            if (count++ >= depth) break;
            BigDecimal totalQty = e.getValue().stream()
                    .map(Order::getRemainingQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            askLevels.add(new PriceLevel(e.getKey(), totalQty, e.getValue().size()));
        }

        return new OrderBookSnapshot(symbol, bidLevels, askLevels);
    }

    public record PriceLevel(BigDecimal price, BigDecimal totalQuantity, int orderCount) {}
    public record OrderBookSnapshot(String symbol, List<PriceLevel> bids, List<PriceLevel> asks) {}
}
