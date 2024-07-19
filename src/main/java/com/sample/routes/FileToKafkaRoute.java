package com.sample.routes;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.component.file.GenericFile;
import org.apache.camel.component.file.GenericFileFilter;
import org.apache.camel.component.kafka.KafkaConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class FileToKafkaRoute extends RouteBuilder {

    @ConfigProperty(name = "kafka.brokers", defaultValue = "localhost:9092")
    String kafka_brokers;

    @ConfigProperty(name = "kafka.deadletter.topic", defaultValue = "deadLetterTopic")
    String topic;
    @ConfigProperty(name = "camel.file.input.directory")
    String inputDirectory;

    private static final int CHUNK_SIZE = 900 * 1024; // 900KB chunks

    @Override
    public void configure() throws Exception {

        GenericFileFilter<File> fileFilter = new GenericFileFilter<File>() {
            @Override
            public boolean accept(GenericFile<File> file) {
                return acceptFile(file.getFile());
            }

            private boolean acceptFile(File file) {
                long fileSize = file.length();
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return file.length() == fileSize && fileSize > 0;
            }
        };
        getContext().getRegistry().bind("fileFilter", fileFilter);

        onException(Exception.class)
                .logStackTrace(true)
                .log(LoggingLevel.ERROR, "Error processing file: ${exception.message}")
                .handled(true)
                .end();

        from("file:" + inputDirectory + "?noop=false&include=.*\\.zip&filter=#fileFilter")
            .routeId("file-to-kafka-route")
            .log("Processing file: ${header.CamelFileName}")
            .setHeader("OriginalFileName", simple("${header.CamelFileName}"))
            .process(exchange -> {
                String fileId = generateFileId();
                exchange.getIn().setHeader("FileId", fileId);
                exchange.getIn().setHeader(KafkaConstants.KEY, fileId);
            })
                .log("Starting split operation")
                .process(exchange -> {
                    byte[] fileContent = exchange.getIn().getBody(byte[].class);
                    String fileName = exchange.getIn().getHeader(Exchange.FILE_NAME, String.class);
                    long fileSize = fileContent != null ? fileContent.length : 0;

                    log.debug("Processing file: {}", fileName);
                    log.debug("File content size: {} bytes", fileSize);

                    if (fileSize == 0) {
                        log.warn("File is empty: {}", fileName);
                        exchange.setProperty("FileIsEmpty", true);
                    } else {
                        exchange.setProperty("FileIsEmpty", false);
                    }
                })
                .choice()
                .when(exchangeProperty("FileIsEmpty").isEqualTo(true))
                .log("Skipping empty file: ${header.CamelFileName}")
                .stop()
                .otherwise()
                .process(this::splitFileIntoChunks)
                .split(body())
                    .setHeader("ChunkIndex", simple("${exchangeProperty.CamelSplitIndex}"))
                    .setHeader("TotalChunks", simple("${exchangeProperty.CamelSplitSize}"))
                    .log("Processing ChunkIndex: ${header.ChunkIndex}\tTotalChunks: ${header.TotalChunks}")
                    .to("kafka:" + topic + "?brokers=" + kafka_brokers)
                    .log("Sent chunk ${header.ChunkIndex} of ${header.TotalChunks} to Kafka for file: ${header.OriginalFileName}")
                .end()
                .log("Finished processing file: ${header.CamelFileName}")
                .end();
    }

    private String generateFileId() {
        return UUID.randomUUID().toString();
    }

    private void splitFileIntoChunks(Exchange exchange) {
        byte[] fileContent = exchange.getIn().getBody(byte[].class);
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < fileContent.length; i += CHUNK_SIZE) {
            int end = Math.min(fileContent.length, i + CHUNK_SIZE);
            chunks.add(Arrays.copyOfRange(fileContent, i, end));
        }
        exchange.getIn().setBody(chunks);
    }
}
