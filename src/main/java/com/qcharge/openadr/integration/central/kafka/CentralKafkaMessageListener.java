package com.qcharge.openadr.integration.central.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class CentralKafkaMessageListener {

    private final CentralKafkaMessageDispatcher dispatcher;

    @KafkaListener(topics = "${kafka.topic.central}", containerFactory = "centralKafkaListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> consumerRecord, Acknowledgment acknowledgment) {
        try {
            dispatcher.dispatch(consumerRecord.value(), Instant.ofEpochMilli(consumerRecord.timestamp()));
            acknowledgment.acknowledge();
        } catch (InvalidCentralMessageException exception) {
            log.warn(
                    "Skipping invalid central telemetry message at partition={}, offset={}: {}",
                    consumerRecord.partition(), consumerRecord.offset(), exception.getMessage()
            );
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Error while processing kafka message", e);
            throw e;
        }
    }
}