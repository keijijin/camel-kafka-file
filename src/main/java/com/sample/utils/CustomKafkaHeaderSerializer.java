package com.sample.utils;

import org.apache.camel.component.kafka.serde.KafkaHeaderSerializer;
import org.apache.camel.component.kafka.serde.KafkaHeaderDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;

public class CustomKafkaHeaderSerializer implements KafkaHeaderSerializer {
    private final StringSerializer delegate = new StringSerializer();

    @Override
    public byte[] serialize(String key, Object value) {
        return delegate.serialize(key, value.toString());
    }
}
