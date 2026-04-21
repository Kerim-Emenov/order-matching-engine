package com.exchange.service;

import com.exchange.kafka.TradeEventPublisher;
import com.exchange.matching.MatchingEngine;
import com.exchange.model.Order;
import com.exchange.model.Trade;
import com.exchange.orderbook.OrderBook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages all per-symbol order books and coordinates the full lifecycle:
 *   submit → match → (rest if limit) → publish events
 *
 * Thread safety: each symbol gets its own ReentrantLock so different symbols
 * can process orders concurrently, but the same symbol is serialized.
 *
 * In production you'd use a single-threaded executor per symbol (actor model)
 * or a LMAX Disruptor ring buffer to avoid lock overhead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookService {

    private final MatchingEngine matchingEngine;
    private final TradeEventPublisher tradeEventPublisher;

    // symbol → OrderBook
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();

    // symbol → lock (per-symbol serialization)
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * Submit an order. Returns the final order state after matching.
     */
    public Order submitOrder(Order order) {
        log.info("Received order: {}", order);
        String symbol = order.getSymbol();

        OrderBook book = books.computeIfAbsent(symbol, OrderBook::new);
        ReentrantLock lock = locks.computeIfAbsent(symbol, k -> new ReentrantLock());

        lock.lock();
        try {
            MatchingEngine.MatchResult result = matchingEngine.match(order, book);
            Order finalOrder = result.finalOrder();
            List<Trade> trades = result.trades();

            // If limit order still has remaining quantity → rest it in the book
            if (finalOrder.getType() == Order.Type.LIMIT
                    && finalOrder.getStatus() != Order.Status.FILLED
                    && finalOrder.getStatus() != Order.Status.CANCELLED) {
                book.addLimitOrder(finalOrder);
            }

            // Publish all generated trades to Kafka
            trades.forEach(trade -> {
                log.info("Publishing trade: {}", trade);
                tradeEventPublisher.publish(trade);
            });

            return finalOrder;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Cancel an open or partially filled order.
     */
    public Optional<Order> cancelOrder(String symbol, String orderId) {
        OrderBook book = books.get(symbol);
        if (book == null) return Optional.empty();

        ReentrantLock lock = locks.computeIfAbsent(symbol, k -> new ReentrantLock());
        lock.lock();
        try {
            return book.findOrder(orderId).map(order -> {
                book.removeOrder(order);
                Order cancelled = order.toBuilder().status(Order.Status.CANCELLED).build();
                log.info("Cancelled order: {}", cancelled);
                return cancelled;
            });
        } finally {
            lock.unlock();
        }
    }

    public Optional<OrderBook.OrderBookSnapshot> getSnapshot(String symbol, int depth) {
        return Optional.ofNullable(books.get(symbol))
                .map(book -> book.snapshot(depth));
    }
}
