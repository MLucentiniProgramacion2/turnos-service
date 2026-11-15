package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Los plazos del productor de CU-06, con la configuración real: los cuatro valores juntos, las dos desigualdades y un
 * productor de Kafka construido de verdad (Kafka valida su configuración al construirlo, sin conectarse).
 */
class CatedraKafkaProducerConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(CatedraKafkaProducerConfiguration.class)
        .withPropertyValues(
            "catedra.kafka.bootstrap-servers=localhost:1",
            "catedra.kafka.actions-topic=acciones-de-prueba",
            "catedra.kafka.consumer-group=grupo-de-prueba",
            "catedra.kafka.phone-topic=telefono-de-prueba",
            "catedra.kafka.group-id=de-prueba"
        );

    @Test
    void losCuatroValoresJuntosYLasDosDesigualdades() {
        runner.run(context -> {
            Map<String, Object> config = context.getBean(ProducerFactory.class).getConfigurationProperties();
            assertThat(config)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 0)
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000)
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000)
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000L)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all");

            // La regla de Kafka: delivery.timeout.ms >= linger.ms + request.timeout.ms.
            assertThat(5_000).isGreaterThanOrEqualTo(0 + 3_000);
            // La del caso de uso: el futuro se espera más que max.block.ms + delivery.timeout.ms.
            CatedraKafkaProperties.Producer plazos = context.getBean(CatedraKafkaProperties.class).getProducer();
            assertThat(plazos.sendTimeout()).isEqualTo(Duration.ofSeconds(8)).isGreaterThan(plazos.getMaxBlock().plus(plazos.getDeliveryTimeout()));
        });
    }

    @Test
    void conLaConfiguracionRealKafkaConstruyeElProductor() {
        runner.run(context -> {
            @SuppressWarnings("unchecked")
            ProducerFactory<String, String> factory = context.getBean(ProducerFactory.class);
            try (Producer<String, String> productor = factory.createProducer()) {
                assertThat(productor).isNotNull();
            }
        });
    }

    @Test
    void conUnDeliveryTimeoutMenorQueElRequestTimeoutNoArranca() {
        runner
            .withPropertyValues("catedra.kafka.producer.delivery-timeout=2s")
            .run(context -> assertThat(context.getStartupFailure()).isNotNull().hasStackTraceContaining("delivery-timeout tiene que ser mayor"));
    }

    @Test
    void elTemplateNoUsaElListenerQueLogueaElRegistro() {
        runner.run(context -> {
            KafkaTemplate<?, ?> template = context.getBean(KafkaTemplate.class);
            assertThat(ReflectionTestUtils.getField(template, "producerListener")).isNotInstanceOf(LoggingProducerListener.class);
        });
    }
}
