package com.exchange;

import com.exchange.matching.MatchingEngine;
import com.exchange.model.Order;
import com.exchange.model.Trade;
import com.exchange.orderbook.OrderBook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingEngineTest {

    private MatchingEngine engine;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine();
        book = new OrderBook("BTC-USD");
    }

    private Order limitBuy(String price, String qty) {
        return new Order(null, "BTC-USD", Order.Side.BUY, Order.Type.LIMIT,
                new BigDecimal(price), new BigDecimal(qty), null, null, null);
    }

    private Order limitSell(String price, String qty) {
        return new Order(null, "BTC-USD", Order.Side.SELL, Order.Type.LIMIT,
                new BigDecimal(price), new BigDecimal(qty), null, null, null);
    }

    private Order marketBuy(String qty) {
        return new Order(null, "BTC-USD", Order.Side.BUY, Order.Type.MARKET,
                null, new BigDecimal(qty), null, null, null);
    }

    private Order marketSell(String qty) {
        return new Order(null, "BTC-USD", Order.Side.SELL, Order.Type.MARKET,
                null, new BigDecimal(qty), null, null, null);
    }

    @Test
    @DisplayName("No match when spread exists — limit buy below ask")
    void noMatchWhenSpreadExists() {
        book.addLimitOrder(limitSell("50000", "1.0")); // ask @ 50000

        Order buy = limitBuy("49999", "1.0"); // bid @ 49999 — doesn't cross
        MatchingEngine.MatchResult result = engine.match(buy, book);

        assertThat(result.trades()).isEmpty();
        assertThat(result.finalOrder().getStatus()).isEqualTo(Order.Status.OPEN);
    }

    @Test
    @DisplayName("Full fill — limit buy crosses the spread exactly")
    void fullFillLimitBuy() {
        Order sell = limitSell("50000", "1.0");
        book.addLimitOrder(sell);

        Order buy = limitBuy("50000", "1.0");
        MatchingEngine.MatchResult result = engine.match(buy, book);

        assertThat(result.trades()).hasSize(1);
        Trade trade = result.trades().get(0);
        assertThat(trade.getPrice()).isEqualByComparingTo("50000");
        assertThat(trade.getQuantity()).isEqualByComparingTo("1.0");
        assertThat(result.finalOrder().getStatus()).isEqualTo(Order.Status.FILLED);
        assertThat(book.getAsks()).isEmpty(); // book cleared
    }

    @Test
    @DisplayName("Partial fill — buyer wants more than available")
    void partialFillBuyer() {
        book.addLimitOrder(limitSell("50000", "0.5")); // only 0.5 available

        Order buy = limitBuy("50000", "1.0"); // wants 1.0
        MatchingEngine.MatchResult result = engine.match(buy, book);

        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().get(0).getQuantity()).isEqualByComparingTo("0.5");
        assertThat(result.finalOrder().getFilledQuantity()).isEqualByComparingTo("0.5");
        assertThat(result.finalOrder().getStatus()).isEqualTo(Order.Status.PARTIALLY_FILLED);
    }

    @Test
    @DisplayName("Multi-level fill — sweeps through multiple ask price levels")
    void multiLevelSweep() {
        book.addLimitOrder(limitSell("50000", "0.3"));
        book.addLimitOrder(limitSell("50100", "0.3"));
        book.addLimitOrder(limitSell("50200", "0.3"));

        Order buy = limitBuy("50200", "0.9"); // willing to pay up to 50200
        MatchingEngine.MatchResult result = engine.match(buy, book);

        // Should sweep all 3 levels = 3 trades
        assertThat(result.trades()).hasSize(3);
        assertThat(result.finalOrder().getStatus()).isEqualTo(Order.Status.FILLED);
        assertThat(book.getAsks()).isEmpty();

        // Execution prices: 50000, 50100, 50200 (passive side sets price)
        List<BigDecimal> prices = result.trades().stream().map(Trade::getPrice).toList();
        assertThat(prices).containsExactly(
                new BigDecimal("50000"),
                new BigDecimal("50100"),
                new BigDecimal("50200"));
    }

    @Test
    @DisplayName("Market order — fully fills against available liquidity")
    void marketOrderFullFill() {
        book.addLimitOrder(limitSell("50000", "1.0"));
        book.addLimitOrder(limitSell("50100", "1.0"));

        Order marketBuy = marketBuy("1.5");
        MatchingEngine.MatchResult result = engine.match(marketBuy, book);

        assertThat(result.trades()).hasSize(2);
        assertThat(result.finalOrder().getFilledQuantity()).isEqualByComparingTo("1.5");
        assertThat(result.finalOrder().getStatus()).isEqualTo(Order.Status.FILLED);
    }

    @Test
    @DisplayName("Market order with no liquidity — cancelled immediately")
    void marketOrderNoLiquidity() {
        // Empty book, no asks
        Order buy = marketBuy("1.0");
        MatchingEngine.MatchResult result = engine.match(buy, book);

        assertThat(result.trades()).isEmpty();
        assertThat(result.finalOrder().getStatus()).isEqualTo(Order.Status.CANCELLED);
    }

    @Test
    @DisplayName("Time priority — two resting orders at same price, earlier one fills first")
    void timePriorityAtSamePrice() {
        Order sell1 = limitSell("50000", "0.5"); // arrives first
        Order sell2 = limitSell("50000", "0.5"); // arrives second
        book.addLimitOrder(sell1);
        book.addLimitOrder(sell2);

        Order buy = limitBuy("50000", "0.5"); // fills only one
        MatchingEngine.MatchResult result = engine.match(buy, book);

        assertThat(result.trades()).hasSize(1);
        // sell1 (first in queue) should have been matched
        assertThat(result.trades().get(0).getSellOrderId()).isEqualTo(sell1.getOrderId());
    }

    @Test
    @DisplayName("Price priority — lower ask fills before higher ask")
    void pricePriorityOnSellSide() {
        Order sellHigh = limitSell("50100", "1.0");
        Order sellLow = limitSell("50000", "1.0");
        book.addLimitOrder(sellHigh); // added first
        book.addLimitOrder(sellLow);  // added second but cheaper

        Order buy = limitBuy("50200", "1.0");
        MatchingEngine.MatchResult result = engine.match(buy, book);

        assertThat(result.trades()).hasSize(1);
        // sellLow should fill despite being added second
        assertThat(result.trades().get(0).getPrice()).isEqualByComparingTo("50000");
    }
}
