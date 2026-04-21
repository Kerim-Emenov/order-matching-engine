package com.exchange.kafka;

import com.exchange.model.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Example Kafka consumer for trade events.
 *
 * In a real system this would be a SEPARATE service — e.g.:
 *   - Risk engine: check position limits after each trade
 *   - Portfolio service: update P&L
 *   - Market data service: publish price ticks to WebSocket clients
 *   - Clearing service: settle obligations
 *
 * This is included here just to show the consumption side of the event bus.
 */
@Slf4j
@Component
public class TradeEventConsumer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @KafkaListener(
            topics = "${exchange.kafka.trade-topic:trade-events}",
            groupId = "${exchange.kafka.consumer-group:trade-processor}"
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
            Trade trade = MAPPER.readValue(record.value(), Trade.class);
            log.info("[CONSUMER] Trade received: symbol={} price={} qty={} tradeId={}",
                    trade.getSymbol(), trade.getPrice(), trade.getQuantity(), trade.getTradeId());

            // Here you would: update portfolio state, publish to WebSocket, update market data, etc.

        } catch (Exception e) {
            log.error("Failed to process trade event: {}", record.value(), e);
            // In production: send to dead-letter topic
        }
    }
}
