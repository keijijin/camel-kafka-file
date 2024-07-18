package com.sample.routes;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ApplicationScoped
public class KafkaToFileRoute extends RouteBuilder {

    @ConfigProperty(name = "camel.file.output.directory")
    String outputDirectory;

    private final Map<String, byte[][]> fileChunks = new ConcurrentHashMap<>();

    @Override
    public void configure() throws Exception {
        from("kafka:deadLetterTopic?brokers=localhost:9092&groupId=deadLetterGroup")
            .routeId("kafka-to-file-route")
            .log("Received message from Kafka: ${header.OriginalFileName}")
                .process(this::processChunk)
                .choice()
                    .when(this::isFileComplete)
                        .process(this::reconstructFile)
                        .log("Reconstrutured file: ${header.OriginalFileName}")
                .end();
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
        Integer tempTotalChunks = exchange.getIn().getHeader("TotalChunks", Integer.class);
        if (tempTotalChunks == null) {
            String totalChunksStr = exchange.getIn().getHeader("TotalChunks", String.class);
            if (totalChunksStr != null && !totalChunksStr.isEmpty()) {
                try {
                    tempTotalChunks = Integer.parseInt(totalChunksStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid TotalChunks: {}", totalChunksStr);
                }
            }
        }
        final Integer totalChunks = tempTotalChunks;
        byte[] chunkData = exchange.getIn().getBody(byte[].class);

        if (fileId == null || chunkIndex == null || totalChunks == null) {
            log.warn("Missing required headers. FileId: {}, ChunkIndex: {}, TotalChunks: {}", fileId, chunkIndex, totalChunks);
            return;
        }

        log.info("Processing chunk: FileId={}, ChunkIndex={}, TotalChunks={}", fileId, chunkIndex, totalChunks);

        fileChunks.computeIfAbsent(fileId, k -> new byte[totalChunks][])
                [chunkIndex] = chunkData;
    }

    private boolean isFileComplete(Exchange exchange) {
        String fileId = exchange.getIn().getHeader("FileId", String.class);
        Integer totalChunks = exchange.getIn().getHeader("TotalChunks", Integer.class);

        if (fileId == null || totalChunks == null) {
            return false;
        }

        byte[][] chunks = fileChunks.get(fileId);
        return chunks != null && chunks.length == totalChunks &&
                IntStream.range(0, totalChunks)
                        .allMatch(i -> chunks[i] != null);
    }

    private void reconstructFile(Exchange exchange) throws Exception {
        String fileId = exchange.getIn().getHeader("FileId", String.class);
        String fileName = exchange.getIn().getHeader("OriginalFileName", String.class);
        byte[][] chunks = fileChunks.get(fileId);

        File outputDir = new File(outputDirectory);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File outputFile = new File(outputDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (byte[] chunk : chunks) {
                fos.write(chunk);
            }
        }

        fileChunks.remove(fileId);
        log.info("Reconstructed file saved: {}", outputFile.getAbsolutePath());
    }
}