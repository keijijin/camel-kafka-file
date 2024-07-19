# Camel Kafka File Integration Project

このプロジェクトは、Quarkusを使用してApache CamelをKafkaおよびファイルシステムと統合する方法を示しています。プロジェクトには、Kafkaヘッダーのカスタムシリアライザーおよびデシリアライザーと、ファイルとKafkaトピック間でデータを転送するルートが含まれています。

## プロジェクト構成

- `pom.xml`: 依存関係とビルドプラグインを含むMaven設定ファイル。
- `application.properties`: アプリケーションの設定ファイル。
- `FileToKafkaRoute.java`: ファイルからデータを読み取り、Kafkaに送信するCamelルート。
- `KafkaToFileRoute.java`: Kafkaからデータを読み取り、ファイルに書き込むCamelルート。
- `CustomKafkaHeaderSerializer.java`: Kafkaヘッダーのカスタムシリアライザー。
- `CustomKafkaHeaderDeserializer.java`: Kafkaヘッダーのカスタムデシリアライザー。

## 前提条件

- Java 17
- Maven 3.8.1以降
- Quarkus 3.8.5
- Apache Kafka

## 始めに

1. **リポジトリをクローン:**

   ```sh
   git clone https://github.com/your-repo/camel-kafka-file.git
   cd camel-kafka-file
   ```

2. **プロジェクトをビルド:**

   ```sh
   mvn clean install
   ```

3. **アプリケーションを実行:**

   ```sh
   mvn quarkus:dev
   ```

## 設定

アプリケーションの設定は`application.properties`ファイルで管理されます。

```properties
kafka.brokers=localhost:9092
kafka.deadletter.topic = deadLetterTopic
kafka.consumer.group = deadLetterGroup

camel.component.kafka.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer
camel.component.kafka.value-deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer
camel.component.kafka.key-serializer=org.apache.kafka.common.serialization.StringSerializer
camel.component.kafka.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
camel.component.kafka.header-serializer=#class:com.sample.utils.CustomKafkaHeaderSerializer
camel.component.kafka.header-deserializer=#class:com.sample.utils.CustomKafkaHeaderDeserializer

camel.file.input.directory=/Users/kjin/Camel/data/input
camel.file.output.directory=/Users/kjin/Camel/data/output

file.transfer.timeout = 300000
```

## ルート

### FileToKafkaRoute

このルートは指定されたディレクトリからファイルを読み取り、その内容をKafkaトピックに送信します。

```java
from("file:{{camel.file.input.directory}}?noop=false&include=.*\\.zip&filter=#fileFilter")
    .routeId("file-to-kafka-route")
    .log("Processing file: ${header.CamelFileName}")
    .setHeader("OriginalFileName", simple("${header.CamelFileName}"))
    .process(exchange -> {
        String fileId = UUID.randomUUID().toString();
        exchange.getIn().setHeader("FileId", fileId);
        exchange.getIn().setHeader(KafkaConstants.KEY, fileId);
    })
    .log("Starting split operation")
    .process(exchange -> {
        byte[] fileContent = exchange.getIn().getBody(byte[].class);
        long fileSize = fileContent != null ? fileContent.length : 0;

        if (fileSize == 0) {
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
                .to("kafka:{{kafka.topic.output}}")
                .log("Sent chunk ${header.ChunkIndex} of ${header.TotalChunks} to Kafka for file: ${header.OriginalFileName}")
            .end()
    .log("Finished processing file: ${header.CamelFileName}")
    .end();
```

### KafkaToFileRoute

このルートはKafkaトピックからメッセージを読み取り、指定されたディレクトリに書き込みます。

```java
from("kafka:{{kafka.deadletter.topic}}?brokers={{kafka.bootstrap.servers}}&groupId=my-consumer-group")
    .routeId("kafka-to-file-route")
    .log("Received message from Kafka: ${header.OriginalFileName}")
    .process(this::processChunk)
    .choice()
        .when(this::isFileComplete)
            .process(this::reconstructFile)
            .log("Reconstructed file: ${header.OriginalFileName}")
    .end();

from("timer:fileTimeout?period=60000")
    .process(this::checkAllFilesTimeout);
```

## カスタムKafkaヘッダーシリアライザーとデシリアライザー

プロジェクトには、Kafkaメッセージのヘッダーを処理するためのカスタムシリアライザーおよびデシリアライザーが含まれています。

### CustomKafkaHeaderSerializer

Kafkaメッセージのカスタムヘッダーをシリアライズします。

```java
public class CustomKafkaHeaderSerializer implements KafkaHeaderSerializer {
    private final StringSerializer delegate = new StringSerializer();

    @Override
    public byte[] serialize(String key, Object value) {
        return delegate.serialize(key, value.toString());
    }
}
```

### CustomKafkaHeaderDeserializer

Kafkaメッセージのカスタムヘッダーをデシリアライズします。

```java
public class CustomKafkaHeaderDeserializer implements KafkaHeaderDeserializer {
    private final StringDeserializer delegate = new StringDeserializer();

    @Override
    public Object deserialize(String key, byte[] value) {
        return delegate.deserialize(key, value);
    }
}
```

## 処理の概要と工夫したポイント

### 処理の概要

1. **FileToKafkaRoute.java**:
    - ローカルファイルシステムからファイルを読み込み、その内容をKafkaトピックに送信します。
    - ファイル名や内容をログに記録します。
    - ファイルの完全なコピーが確認されるまで処理を開始しません 。

2. **KafkaToFileRoute.java**:
    - Kafkaトピックからメッセージを読み込み、ローカルファイルシステムに書き込みます。
    - 受信したメッセージの内容をログに記録します。
    - メッセージのチャンクを順序通りに再構成し、タイムアウトを監視します  【9†source】 。

### 工夫したポイント

- **完全なファイルコピーの確認**:
    - ファイルが完全にコピーされるのを待つために、ファイルサイズが変わらないことを確認しています。これにより、未完全なファイルを処理することを防ぎます  。

- **チャンクの順序保証と再構成**:
    - ファイルを900KBのチャンクに分割し、それぞれのチャンクをKafkaに送信します  。
    - Kafkaから受信したチャンクをTreeMapを使用して順序通りに保持し、全てのチャンクが揃ったら再構成してファイルに書き出します  【9†source】 。
    - タイムアウトを監視し、一定時間以上活動がない場合はエラー処理を実行します 【9†source】 。

このREADMEがプロジェクトの理解とセットアップに役立つことを願っています。詳細や質問がある場合は、お気軽にお問い合わせください。
