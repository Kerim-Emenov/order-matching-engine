package com.exchange.api;

import com.exchange.model.Order;
import com.exchange.orderbook.OrderBook;
import com.exchange.service.OrderBookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for the order matching engine.
 *
 * Endpoints:
 *   POST   /api/orders                   → Submit a new order
 *   DELETE /api/orders/{symbol}/{orderId} → Cancel an open order
 *   GET    /api/orderbook/{symbol}        → Query the current order book snapshot
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderController {

    private final OrderBookService orderBookService;

    /**
     * Submit a new order.
     *
     * Example request body:
     * {
     *   "symbol": "BTC-USD",
     *   "side": "BUY",
     *   "type": "LIMIT",
     *   "price": "50000.00",
     *   "quantity": "0.5"
     * }
     */
    @PostMapping("/orders")
    public ResponseEntity<Order> submitOrder(@RequestBody Order order) {
        if (order.getSymbol() == null || order.getSide() == null || order.getType() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (order.getType() == Order.Type.LIMIT && order.getPrice() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (order.getQuantity() == null || order.getQuantity().signum() <= 0) {
            return ResponseEntity.badRequest().build();
        }

        Order result = orderBookService.submitOrder(order);
        return ResponseEntity.ok(result);
    }

    /**
     * Cancel an open or partially filled order.
     */
    @DeleteMapping("/orders/{symbol}/{orderId}")
    public ResponseEntity<Order> cancelOrder(
            @PathVariable String symbol,
            @PathVariable String orderId) {
        return orderBookService.cancelOrder(symbol, orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get a snapshot of the current order book.
     * depth param controls how many price levels to return per side (default 10).
     */
    @GetMapping("/orderbook/{symbol}")
    public ResponseEntity<OrderBook.OrderBookSnapshot> getOrderBook(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "10") int depth) {
        return orderBookService.getSnapshot(symbol, depth)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }
}
