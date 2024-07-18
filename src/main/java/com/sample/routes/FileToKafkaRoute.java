package com.sample.routes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class FileToKafkaRoute extends RouteBuilder {

    @ConfigProperty(name = "camel.file.input.directory")
    String inputDirectory;

    private static final int CHUNK_SIZE = 900 * 1024; // 900KB chunks

    @Override
    public void configure() throws Exception {
        from("file:" + inputDirectory + "?noop=false&include=.*\\.zip")
            .routeId("file-to-kafka-route")
            .log("Processing file: ${header.CamelFileName}")
            .setHeader("OriginalFileName", simple("${header.CamelFileName}"))
            .process(exchange -> {
                String fileId = generateFileId();
                exchange.getIn().setHeader("FileId", fileId);
            })
            .process(this::splitFileIntoChunks)
            .split(body())
                .setHeader("ChunkIndex", simple("${exchangeProperty.CamelSplitIndex}"))
                .setHeader("TotalChunks", simple("${exchangeProperty.CamelSplitSize}"))
                .log("ChunkIndex: ${header.ChunkIndex}\nTotalChunks: ${header.TotalChunks}")
                .to("kafka:deadLetterTopic?brokers=localhost:9092")
                .log("Sent chunk ${header.ChunkIndex} of ${header.TotalChunks} to Kafka for file: ${header.OriginalFileName}")
            .end();
    }

    private String generateFileId() {
        return UUID.randomUUID().toString();
    }

    private void splitFileIntoChunks(Exchange exchange) throws Exception {
        byte[] fileContent = exchange.getIn().getBody(byte[].class);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(fileContent);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK_SIZE];
        int bytesRead;
        int chunkIndex = 0;
        List<byte[]> chunks = new ArrayList<>();

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
            if (outputStream.size() >= CHUNK_SIZE) {
                chunks.add(outputStream.toByteArray());
                outputStream.reset();
                chunkIndex++;
            }
        }

        if (outputStream.size() > 0) {
            chunks.add(outputStream.toByteArray());
        }

        exchange.getIn().setBody(chunks);
    }
}