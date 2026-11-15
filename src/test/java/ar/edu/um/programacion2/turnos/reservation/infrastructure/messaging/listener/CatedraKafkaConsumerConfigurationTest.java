package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Sin bootstrap, topic o grupo la aplicación no arranca, y el consumidor queda como pide el ADR 0006. Levanta solo la
 * configuración, sin broker ni base.
 */
@ExtendWith(OutputCaptureExtension.class)
class CatedraKafkaConsumerConfigurationTest {

    private static final String BOOTSTRAP = "catedra.kafka.bootstrap-servers=broker.test:9092";
    private static final String TOPIC = "catedra.kafka.actions-topic=alumnos.turnos.acciones.de-prueba";
    private static final String GRUPO = "catedra.kafka.consumer-group=alumnos-de-prueba-turnos";
    private static final ConsumerRecord<String, String> EVENTO = new ConsumerRecord<>("alumnos.turnos.acciones.de-prueba", 0, 127, "process-a92b47d0", "{}");

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CatedraKafkaConsumerConfiguration.class);

    @Test
    void conLasTresVariablesArrancaConElListenerPrendidoYLosValoresDeDpt22() {
        runner
            .withPropertyValues(BOOTSTRAP, TOPIC, GRUPO)
            .run(context -> {
                assertThat(context).hasNotFailed();
                CatedraKafkaProperties properties = context.getBean(CatedraKafkaProperties.class);
                assertThat(properties.isListenerEnabled()).isTrue();
                assertThat(properties.getRetryDelays()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
                assertThat(properties.getMaxPollInterval()).isEqualTo(Duration.ofMinutes(2));
            });
    }

    @ParameterizedTest(name = "sin {0}")
    @MethodSource("configuracionesIncompletas")
    void sinAlgunaVariableNoArranca(String nombre, String[] propiedades) {
        runner
            .withPropertyValues(propiedades)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(nombre);
            });
    }

    static Stream<Arguments> configuracionesIncompletas() {
        return Stream.of(
            Arguments.of("bootstrapServers", new String[] { TOPIC, GRUPO }),
            Arguments.of("actionsTopic", new String[] { BOOTSTRAP, GRUPO }),
            Arguments.of("consumerGroup", new String[] { BOOTSTRAP, TOPIC }),
            Arguments.of("bootstrapServers", new String[] { "catedra.kafka.bootstrap-servers=", TOPIC, GRUPO }),
            Arguments.of("actionsTopic", new String[] { BOOTSTRAP, "catedra.kafka.actions-topic=", GRUPO }),
            Arguments.of("consumerGroup", new String[] { BOOTSTRAP, TOPIC, "catedra.kafka.consumer-group=" })
        );
    }

    @Test
    void conElListenerApagadoTampocoArrancaSinVariables() {
        // Es lo que justifica validarlas acá: sin consumidor, Kafka nunca se quejaría.
        runner.withPropertyValues("catedra.kafka.listener-enabled=false").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void elConsumidorQuedaComoPideElAdr() {
        runner
            .withPropertyValues(BOOTSTRAP, TOPIC, GRUPO)
            .run(context -> {
                assertThat(context.getBean(ConsumerFactory.class).getConfigurationProperties())
                    .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker.test:9092")
                    .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "alumnos-de-prueba-turnos")
                    .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                    .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                    .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1)
                    .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 120_000)
                    .containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
                ConcurrentKafkaListenerContainerFactory<?, ?> factory = context.getBean(ConcurrentKafkaListenerContainerFactory.class);
                assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(AckMode.RECORD);
                assertThat(factory.getContainerProperties().getAuthExceptionRetryInterval()).isEqualTo(Duration.ofMinutes(1));
            });
    }

    @Test
    void lasEsperasEntreIntentosSonLasDeDpt22YDespuesSeTermina() {
        BackOffExecution esperas = CatedraKafkaConsumerConfiguration.backOff(
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))
        ).start();

        List<Long> obtenidas = new ArrayList<>();
        for (long espera = esperas.nextBackOff(); espera != BackOffExecution.STOP; espera = esperas.nextBackOff()) {
            obtenidas.add(espera);
        }
        assertThat(obtenidas).containsExactly(1000L, 2000L, 4000L);
    }

    @Test
    void unaFallaDelCommitDelOffsetLlegaSinEnvolverYSeLogueaConSuClaseYMensaje(CapturedOutput salida) {
        CommitFailedException commit = new CommitFailedException("el consumidor quedó fuera del grupo");

        CatedraKafkaConsumerConfiguration.retryListener().failedDelivery(EVENTO, commit, 1);

        assertThat(salida.getAll())
            .contains(
                "Evento de reserva: falló el intento 1 (partición 0, offset 127, clave process-a92b47d0): org.apache.kafka.clients.consumer.CommitFailedException: el consumidor quedó fuera del grupo"
            )
            .doesNotContain("process-a92b47d0): null");
    }

    @Test
    void soloSeDesenvuelveListenerExecutionFailedException() {
        ListenerExecutionFailedException envuelta = new ListenerExecutionFailedException("Listener failed", new DataAccessResourceFailureException("sin conexión"));
        IllegalStateException otra = new IllegalStateException("afuera", new IllegalArgumentException("adentro"));

        assertThat(CatedraKafkaConsumerConfiguration.describe(envuelta)).isEqualTo("org.springframework.dao.DataAccessResourceFailureException: sin conexión");
        assertThat(CatedraKafkaConsumerConfiguration.describe(otra)).isEqualTo("java.lang.IllegalStateException: afuera");
        assertThat(CatedraKafkaConsumerConfiguration.describe(new IllegalStateException())).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void elRecovererNuncaLanzaYDejaUnErrorSinElCuerpoDelMensaje(CapturedOutput salida) {
        ConsumerRecord<String, String> conDatos = new ConsumerRecord<>("t", 0, 9, "process-a92b47d0", "{\"phoneNumber\":\"+5492615551234\"}");

        new ReservationEventRecoverer().accept(conDatos, new IllegalStateException("la base no responde"));

        assertThat(salida.getAll())
            .contains("Evento de reserva descartado después de los reintentos (partición 0, offset 9, clave process-a92b47d0)")
            .doesNotContain("5551234");
    }
}
