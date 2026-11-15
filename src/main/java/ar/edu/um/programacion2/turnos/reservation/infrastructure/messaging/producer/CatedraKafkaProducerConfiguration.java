package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;

/**
 * El productor del topic de teléfono (CU-06), armado en Java con {@code catedra.kafka.*} como el consumidor (ADR 0006). Con
 * estos beans, la autoconfiguración de Spring Boot no crea los suyos: ninguna propiedad {@code spring.kafka.*} llega acá.
 * <ul>
 * <li>{@code acks=all}: el broker confirma cuando el registro quedó escrito en las réplicas en sincronía (el contrato
 * pide esperar la confirmación, CU-06 paso 6).</li>
 * <li>Los plazos de {@link CatedraKafkaProperties.Producer} (2, 3 y 5 s), en vez de los 60 y 120 s por defecto.</li>
 * <li>{@code linger.ms=0}: se manda enseguida, no se espera a juntar un lote.</li>
 * <li>Idempotencia del productor (por defecto en Kafka 3 o más): si Kafka reintenta un envío por su cuenta, el broker
 * descarta el duplicado. No reemplaza la idempotencia funcional: cada envío de CU-06 lleva un {@code eventId} nuevo.</li>
 * </ul>
 * <strong>El {@code KafkaTemplate} no loguea los fallos con su listener por defecto</strong>
 * ({@code LoggingProducerListener}), que escribe parte del valor del registro en el log: ahí va el teléfono. El adaptador
 * loguea por su cuenta, sin el cuerpo.
 */
@Configuration
@EnableConfigurationProperties(CatedraKafkaProperties.class)
public class CatedraKafkaProducerConfiguration {

    @Bean
    public ProducerFactory<String, String> phoneProducerFactory(CatedraKafkaProperties properties) {
        CatedraKafkaProperties.Producer producer = properties.getProducer();
        return new DefaultKafkaProducerFactory<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.ACKS_CONFIG,
                "all",
                ProducerConfig.LINGER_MS_CONFIG,
                0,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                producer.getMaxBlock().toMillis(),
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                Math.toIntExact(producer.getRequestTimeout().toMillis()),
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                Math.toIntExact(producer.getDeliveryTimeout().toMillis())
            )
        );
    }

    @Bean
    public KafkaTemplate<String, String> phoneKafkaTemplate(ProducerFactory<String, String> phoneProducerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(phoneProducerFactory);
        // Un listener que no hace nada: el de por defecto loguea el registro que falló, con el teléfono adentro.
        template.setProducerListener(new ProducerListener<>() {});
        return template;
    }
}
