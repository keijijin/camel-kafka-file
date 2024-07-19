package com.sample.routes;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class KafkaToFileRoute extends RouteBuilder {

    @ConfigProperty(name = "kafka.brokers", defaultValue = "localhost:9092")
    String kafka_brokers;

    @ConfigProperty(name = "kafka.deadletter.topic", defaultValue = "deadLetterTopic")
    String topic;

    @ConfigProperty(name = "kafka.consumer.group", defaultValue = "deadLetterGroup")
    String consumerGroup;
    @ConfigProperty(name = "camel.file.output.directory")
    String outputDirectory;

    @ConfigProperty(name = "file.transfer.timeout", defaultValue = "300000")
    long timeout;

    private final Map<String, TreeMap<Integer, byte[]>> fileChunks = new ConcurrentHashMap<>();
    private final Map<String, Long> fileLastActivityTimestamps = new ConcurrentHashMap<>();

    @Override
    public void configure() throws Exception {
        from("kafka:" + topic + "?brokers=" + kafka_brokers + "&groupId=" + consumerGroup)
            .routeId("kafka-to-file-route")
            .log("Received message from Kafka: ${header.OriginalFileName}")
                .process(this::processChunk)
                .choice()
                    .when(this::isFileComplete)
                        .process(this::reconstructFile)
                        .log("Reconstrutured file: ${header.OriginalFileName}")
                .end();

        from("timer:fileTimeout?period=60000")
                .process(this::checkAllFilesTimeout);
    }

    private void processChunk(Exchange exchange) {
        String fileId = exchange.getIn().getHeader("FileId", String.class);
        Integer chunkIndex = exchange.getIn().getHeader("ChunkIndex", Integer.class);
        if (chunkIndex == null) {
            String chunkIndexStr = exchange.getIn().getHeader("ChunkIndex", String.class);
            if (chunkIndexStr != null && !chunkIndexStr.isEmpty()) {
                try {
                    chunkIndex = Integer.parseInt(chunkIndexStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid ChunkIndex: {}", chunkIndexStr);
                }
            }
         }
        Integer totalChunks = exchange.getIn().getHeader("TotalChunks", Integer.class);
        if (totalChunks == null) {
            String totalChunksStr = exchange.getIn().getHeader("TotalChunks", String.class);
            if (totalChunksStr != null && !totalChunksStr.isEmpty()) {
                try {
                    totalChunks = Integer.parseInt(totalChunksStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid TotalChunks: {}", totalChunksStr);
                }
            }
        }
        byte[] chunkData = exchange.getIn().getBody(byte[].class);

        if (fileId == null || chunkIndex == null || totalChunks == null) {
            log.warn("Missing required headers. FileId: {}, ChunkIndex: {}, TotalChunks: {}",
                    fileId, chunkIndex, totalChunks);
            return;
        }

        log.info("Processing chunk: FileId={}, ChunkIndex={}, TotalChunks={}", fileId, chunkIndex, totalChunks);

        fileChunks.computeIfAbsent(fileId, k -> new TreeMap<>()).put(chunkIndex, chunkData);
        fileLastActivityTimestamps.put(fileId, System.currentTimeMillis());
    }

    private boolean isFileComplete(Exchange exchange) {
        String fileId = exchange.getIn().getHeader("FileId", String.class);
        Integer totalChunks = exchange.getIn().getHeader("TotalChunks", Integer.class);

        if (fileId == null || totalChunks == null) {
            return false;
        }

        TreeMap<Integer, byte[]> chunks = fileChunks.get(fileId);
        return chunks != null && chunks.size() == totalChunks &&
                chunks.firstKey() == 0 && chunks.lastKey() == totalChunks -1;
    }

    private void reconstructFile(Exchange exchange) throws Exception {
        String fileId = exchange.getIn().getHeader("FileId", String.class);
        String fileName = exchange.getIn().getHeader("OriginalFileName", String.class);
        TreeMap<Integer, byte[]> chunks = fileChunks.get(fileId);

        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File outputFile = new File(outputDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (byte[] chunk : chunks.values()) {
                fos.write(chunk);
            }
        }

        fileChunks.remove(fileId);
        fileLastActivityTimestamps.remove(fileId);
        log.info("Reconstructed file saved: {}", outputFile.getAbsolutePath());
    }

    private void checkAllFilesTimeout(Exchange exchange) {
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : new HashMap<>(fileLastActivityTimestamps).entrySet()) {
            String fileId = entry.getKey();
            long lastActivityTime = entry.getValue();
            if (currentTime - lastActivityTime > timeout) {
                log.error("Timeout occurred for file: {}. Last activity: {} ms ago", fileId, currentTime - lastActivityTime);
                // ここで再送要求やエラー処理を実装
                fileChunks.remove(fileId);
                fileLastActivityTimestamps.remove(fileId);
            }
        }
    }
}