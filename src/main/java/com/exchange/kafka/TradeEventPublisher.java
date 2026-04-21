package com.exchange.kafka;

import com.exchange.model.Trade;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes TradeEvent messages to Kafka.
 *
 * Topic partitioning: keyed by symbol so all trades for a given symbol
 * are ordered within a partition. Consumers can parallelize by symbol.
 *
 * In production you'd also want:
 *   - idempotent producer (enable.idempotence=true)
 *   - acks=all for durability
 *   - dead-letter topic for failed publishes
 */
@Slf4j
@Component
public class TradeEventPublisher {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public TradeEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${exchange.kafka.trade-topic:trade-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(Trade trade) {
        try {
            String payload = MAPPER.writeValueAsString(trade);
            // Key by symbol → same partition for same symbol → ordered delivery
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, trade.getSymbol(), payload);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish trade {}: {}", trade.getTradeId(), ex.getMessage());
                } else {
                    log.debug("Published trade {} to partition {}",
                            trade.getTradeId(),
                            result.getRecordMetadata().partition());
                }
            });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize trade: {}", trade, e);
        }
    }
}
