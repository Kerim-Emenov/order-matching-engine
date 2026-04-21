package com.exchange.matching;

import com.exchange.model.Order;
import com.exchange.model.Trade;
import com.exchange.orderbook.OrderBook;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.*;

/**
 * The Matching Engine — where the actual price-time priority matching happens.
 *
 * Matching rules:
 *   LIMIT BUY:    matches against asks where ask.price <= order.price (crossing the spread)
 *   LIMIT SELL:   matches against bids where bid.price >= order.price
 *   MARKET BUY:   matches against all available asks at any price (dangerous in thin markets)
 *   MARKET SELL:  matches against all available bids at any price
 *
 * Execution price = the resting order's price (the passive side).
 * This is standard "maker" pricing — the order already in the book sets the price.
 *
 * Partial fills: an order may be matched against multiple resting orders at different price levels.
 * The remaining unfilled quantity of a LIMIT order rests in the book.
 * The remaining unfilled quantity of a MARKET order is cancelled (no resting allowed).
 */
@Slf4j
public class MatchingEngine {

    /**
     * Process an incoming order against the book. Returns all trades generated.
     * The book is modified in-place (matched orders removed/updated).
     * If the incoming order is a LIMIT order and has remaining quantity, it should be added to the book
     * by the caller after this method returns.
     */
    public MatchResult match(Order incomingOrder, OrderBook book) {
        return switch (incomingOrder.getType()) {
            case LIMIT -> matchLimit(incomingOrder, book);
            case MARKET -> matchMarket(incomingOrder, book);
        };
    }

    private MatchResult matchLimit(Order order, OrderBook book) {
        List<Trade> trades = new ArrayList<>();
        Order current = order;

        if (order.getSide() == Order.Side.BUY) {
            // Match against asks: consume asks where price <= our bid price
            while (current.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
                Optional<Map.Entry<BigDecimal, Deque<Order>>> bestAsk = book.bestAsk();
                if (bestAsk.isEmpty()) break;

                BigDecimal askPrice = bestAsk.get().getKey();
                if (askPrice.compareTo(current.getPrice()) > 0) {
                    // Best ask is above our limit — no match possible
                    break;
                }

                MatchStep step = executeMatch(current, bestAsk.get().getValue(), askPrice, book);
                trades.addAll(step.trades());
                current = step.updatedIncoming();
            }
        } else {
            // SELL: match against bids where price >= our ask price
            while (current.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
                Optional<Map.Entry<BigDecimal, Deque<Order>>> bestBid = book.bestBid();
                if (bestBid.isEmpty()) break;

                BigDecimal bidPrice = bestBid.get().getKey();
                if (bidPrice.compareTo(current.getPrice()) < 0) {
                    break;
                }

                MatchStep step = executeMatch(current, bestBid.get().getValue(), bidPrice, book);
                trades.addAll(step.trades());
                current = step.updatedIncoming();
            }
        }

        // Determine final status of the incoming order
        Order finalOrder = finalize(current);
        return new MatchResult(finalOrder, trades);
    }

    private MatchResult matchMarket(Order order, OrderBook book) {
        List<Trade> trades = new ArrayList<>();
        Order current = order;

        TreeMap<BigDecimal, Deque<Order>> opposingSide =
                order.getSide() == Order.Side.BUY ? book.getAsks() : book.getBids();

        while (current.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0
                && !opposingSide.isEmpty()) {
            Map.Entry<BigDecimal, Deque<Order>> bestLevel = order.getSide() == Order.Side.BUY
                    ? book.bestAsk().orElseThrow()
                    : book.bestBid().orElseThrow();

            MatchStep step = executeMatch(current, bestLevel.getValue(), bestLevel.getKey(), book);
            trades.addAll(step.trades());
            current = step.updatedIncoming();
        }

        // Market orders do not rest — remaining qty is cancelled
        Order finalOrder;
        if (current.getRemainingQuantity().compareTo(BigDecimal.ZERO) == 0) {
            finalOrder = current.toBuilder().status(Order.Status.FILLED).build();
        } else if (current.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0) {
            // Partially matched, remaining cancelled (market order can't rest)
            finalOrder = current.toBuilder().status(Order.Status.CANCELLED).build();
            log.warn("Market order {} partially filled, remaining {} cancelled — insufficient liquidity",
                    current.getOrderId(), current.getRemainingQuantity());
        } else {
            // Nothing filled at all
            finalOrder = current.toBuilder().status(Order.Status.CANCELLED).build();
            log.warn("Market order {} cancelled — no liquidity available", current.getOrderId());
        }

        return new MatchResult(finalOrder, trades);
    }

    /**
     * Execute matches at a single price level, consuming resting orders from the front of the queue.
     */
    private MatchStep executeMatch(Order incoming, Deque<Order> restingQueue,
                                   BigDecimal executionPrice, OrderBook book) {
        List<Trade> trades = new ArrayList<>();
        Order current = incoming;

        while (!restingQueue.isEmpty() && current.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
            Order resting = restingQueue.peekFirst();
            BigDecimal matchQty = current.getRemainingQuantity()
                    .min(resting.getRemainingQuantity());

            // Create the trade
            Order buyOrder = current.getSide() == Order.Side.BUY ? current : resting;
            Order sellOrder = current.getSide() == Order.Side.SELL ? current : resting;
            Trade trade = Trade.of(book.getSymbol(), buyOrder, sellOrder, executionPrice, matchQty);
            trades.add(trade);

            log.info("TRADE: {} @ {} x {} [buyer={}, seller={}]",
                    book.getSymbol(), executionPrice, matchQty,
                    buyOrder.getOrderId(), sellOrder.getOrderId());

            // Update resting order
            Order updatedResting = resting.toBuilder()
                    .filledQuantity(resting.getFilledQuantity().add(matchQty))
                    .status(resting.getFilledQuantity().add(matchQty).compareTo(resting.getQuantity()) >= 0
                            ? Order.Status.FILLED : Order.Status.PARTIALLY_FILLED)
                    .build();

            if (updatedResting.isFilled()) {
                restingQueue.pollFirst(); // remove from level
                book.removeOrder(resting);
            } else {
                restingQueue.pollFirst();
                restingQueue.addFirst(updatedResting); // update in-place
                book.replaceOrder(resting, updatedResting);
            }

            // Update incoming order
            current = current.toBuilder()
                    .filledQuantity(current.getFilledQuantity().add(matchQty))
                    .status(current.getFilledQuantity().add(matchQty).compareTo(current.getQuantity()) >= 0
                            ? Order.Status.FILLED : Order.Status.PARTIALLY_FILLED)
                    .build();
        }

        return new MatchStep(current, trades);
    }

    private Order finalize(Order order) {
        if (order.isFilled()) {
            return order.toBuilder().status(Order.Status.FILLED).build();
        }
        if (order.getFilledQuantity().compareTo(BigDecimal.ZERO) > 0) {
            return order.toBuilder().status(Order.Status.PARTIALLY_FILLED).build();
        }
        return order; // unchanged, still OPEN
    }

    public record MatchResult(Order finalOrder, List<Trade> trades) {}
    private record MatchStep(Order updatedIncoming, List<Trade> trades) {}
}
