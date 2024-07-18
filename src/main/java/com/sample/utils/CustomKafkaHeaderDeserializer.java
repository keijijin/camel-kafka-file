package com.sample.utils;

import org.apache.camel.component.kafka.serde.KafkaHeaderDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

public class CustomKafkaHeaderDeserializer implements KafkaHeaderDeserializer {
    private final StringDeserializer delegate = new StringDeserializer();

    @Override
    public Object deserialize(String key, byte[] value) {
        return delegate.deserialize(key, value);
    }
}
