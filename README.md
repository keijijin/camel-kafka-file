q# Camel on Quarkusプロジェクト

このプロジェクトは、Apache CamelとQuarkusの統合を示しており、Kafkaとファイル処理に焦点を当てています。

## プロジェクト構成

- `pom.xml`: Maven設定ファイル
- `application.properties`: アプリケーション設定ファイル
- `KafkaToFileRoute.java`: Kafkaから読み込み、ファイルに書き込むルート定義
- `FileToKafkaRoute.java`: ファイルから読み込み、Kafkaに書き込むルート定義
- `CustomKafkaHeaderDeserializer.java`: Kafkaヘッダー用のカスタムデシリアライザー
- `CustomKafkaHeaderSerializer.java`: Kafkaヘッダー用のカスタムシリアライザー

## 前提条件

- Java 17以降
- Maven 3.8.1以降
- Docker（必要に応じてKafkaのセットアップ用）

## 依存関係

このプロジェクトでは、以下の依存関係を使用しています：

```xml
<dependencies>
    <dependency>
        <groupId>org.apache.camel.quarkus</groupId>
        <artifactId>camel-quarkus-file</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.camel.quarkus</groupId>
        <artifactId>camel-quarkus-log</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.camel.quarkus</groupId>
        <artifactId>camel-quarkus-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.camel.quarkus</groupId>
        <artifactId>camel-quarkus-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-arc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.camel.quarkus</groupId>
        <artifactId>camel-quarkus-zipfile</artifactId>
    </dependency>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-junit5</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

## 設定

設定は`application.properties`ファイルで提供されています：

```properties
# Kafkaの設定
kafka.bootstrap.servers=localhost:9092
kafka.topic.input=my-input-topic
kafka.topic.output=my-output-topic

# ファイルパス
file.input.path=/path/to/input/files
file.output.path=/path/to/output/files

# 必要に応じた他の設定
```

## ルート

### Kafkaからファイルへのルート

`KafkaToFileRoute.java`で定義：

```java
public class KafkaToFileRoute extends RouteBuilder {
    @Override
    public void configure() {
        from("kafka:{{kafka.topic.input}}")
            .routeId("KafkaToFile")
            .log("Received message from Kafka: ${body}")
            .to("file:{{file.output.path}}");
    }
}
```

### ファイルからKafkaへのルート

`FileToKafkaRoute.java`で定義：

```java
public class FileToKafkaRoute extends RouteBuilder {
    @Override
    public void configure() {
        from("file:{{file.input.path}}?noop=true")
            .routeId("FileToKafka")
            .log("Read file: ${file:name}")
            .to("kafka:{{kafka.topic.output}}");
    }
}
```

## カスタムシリアライザー/デシリアライザー

### カスタムKafkaヘッダーデシリアライザー

`CustomKafkaHeaderDeserializer.java`で定義：

```java
public class CustomKafkaHeaderDeserializer implements Deserializer<Object> {
    @Override
    public Object deserialize(String topic, byte[] data) {
        // カスタムデシリアライズロジック
    }
}
```

### カスタムKafkaヘッダーシリアライザー

`CustomKafkaHeaderSerializer.java`で定義：

```java
public class CustomKafkaHeaderSerializer implements Serializer<Object> {
    @Override
    public byte[] serialize(String topic, Object data) {
        // カスタムシリアライズロジック
    }
}
```

## プロジェクトの実行

プロジェクトを実行するには、以下のMavenコマンドを使用します：

```bash
mvn clean compile quarkus:dev
```

## プロジェクトのビルド

ネイティブ実行のためにプロジェクトをビルドするには、以下のMavenコマンドを使用します：

```bash
mvn clean package -Pnative
```

これにより、`target`ディレクトリにネイティブ実行ファイルが作成されます。
