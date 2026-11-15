package ar.edu.um.programacion2.turnos.reservation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Un broker real para los tests (como el del catálogo), que hace lo que hace la cátedra: crear los topics (una partición y
 * una réplica, como los reales) y publicar. Lee los offsets confirmados con {@link Admin}, como los ve el broker.
 * <p>
 * Uno solo por corrida. Los nombres de topics y grupos son inventados: los reales van solo en el {@code .env}.
 */
public final class KafkaTestBroker {

    private static final KafkaContainer KAFKA = start();
    private static final Admin ADMIN = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
    private static final KafkaProducer<String, byte[]> PRODUCER = new KafkaProducer<>(
        Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()),
        new StringSerializer(),
        new ByteArraySerializer()
    );

    private KafkaTestBroker() {}

    private static KafkaContainer start() {
        KafkaContainer container = new KafkaContainer("apache/kafka-native:4.1.0");
        container.start();
        return container;
    }

    public static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    /** Crea un topic con un nombre que no se repite y lo devuelve. */
    public static String createTopic(String prefix) {
        String topic = prefix + UUID.randomUUID();
        await(() -> ADMIN.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get());
        return topic;
    }

    /** Publica el valor tal cual, con esa clave: {@code null} es un tombstone. */
    public static void publish(String topic, String key, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        await(() -> PRODUCER.send(new ProducerRecord<>(topic, key, bytes)).get());
    }

    /** Offset confirmado por el grupo en la única partición, o {@code null} si todavía no confirmó nada. */
    public static Long committedOffset(String group, String topic) {
        Map<TopicPartition, OffsetAndMetadata> offsets = await(() -> ADMIN.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get());
        OffsetAndMetadata offset = offsets.get(new TopicPartition(topic, 0));
        return offset == null ? null : offset.offset();
    }

    /** Lee desde el principio del topic hasta tener {@code cuantos} mensajes, con un tope de 20 s. */
    public static List<ConsumerRecord<String, String>> read(String topic, int cuantos) {
        try (
            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    bootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG,
                    "lector-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                    "earliest"
                ),
                new StringDeserializer(),
                new StringDeserializer()
            )
        ) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> leidos = new ArrayList<>();
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (leidos.size() < cuantos && System.nanoTime() < limite) {
                consumer.poll(Duration.ofMillis(300)).forEach(leidos::add);
            }
            return leidos;
        }
    }

    public static Admin admin() {
        return ADMIN;
    }

    private interface BrokerCall<T> {
        T run() throws InterruptedException, ExecutionException;
    }

    private static <T> T await(BrokerCall<T> call) {
        try {
            return call.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Falló el pedido al broker de prueba", e);
        }
    }
}
