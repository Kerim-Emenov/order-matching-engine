# Event-Driven Order Matching Engine

A simplified exchange order book engine with:
- Price-time priority matching (LIMIT & MARKET orders, partial fills)
- Kafka event bus publishing trade events
- REST API for order submission and book queries

## Architecture

```
Client (REST)
     │
     ▼
OrderController (HTTP layer)
     │
     ▼
OrderBookService (orchestration, per-symbol locking)
     │
     ├──► MatchingEngine (pure matching logic, no I/O)
     │         │
     │         └──► OrderBook (per-symbol TreeMap price levels)
     │
     └──► TradeEventPublisher ──► Kafka topic: "trade-events"
                                          │
                               TradeEventConsumer (example downstream)
```

## Running

```bash
# 1. Start Kafka
docker-compose up -d

# 2. Start the application
./mvnw spring-boot:run

# Optional: view Kafka UI at http://localhost:8090
```

## API Examples

### Submit a limit buy order
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTC-USD",
    "side": "BUY",
    "type": "LIMIT",
    "price": "50000.00",
    "quantity": "1.5"
  }'
```

### Submit a market sell order
```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "BTC-USD",
    "side": "SELL",
    "type": "MARKET",
    "quantity": "0.5"
  }'
```

### Cancel an order
```bash
curl -X DELETE http://localhost:8080/api/orders/BTC-USD/{orderId}
```

### View the order book (top 5 levels)
```bash
curl http://localhost:8080/api/orderbook/BTC-USD?depth=5
```

## Key Design Decisions

### Per-symbol locking
Each symbol gets its own `ReentrantLock`. Orders for BTC-USD and ETH-USD
can be processed concurrently, but two BTC-USD orders are serialized.
In production: use a single-threaded executor per symbol (actor model)
or a LMAX Disruptor ring buffer to avoid lock contention entirely.

### Execution price = passive side
When a buy order at $50,100 matches a resting sell at $50,000,
the execution price is $50,000 (the maker). This is standard exchange behavior.

### Market order semantics
Market orders cannot rest in the book. If a market order cannot be fully
filled (insufficient liquidity), the remaining quantity is cancelled immediately.

### Kafka keying
Trades are published with `symbol` as the Kafka message key. This ensures
all trades for a given symbol land in the same partition → ordered delivery
to downstream consumers.
