package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Consumidor del topic de acciones de la cátedra (CU-05 y CU-07), armado en Java a partir de {@code catedra.kafka.*}.
 * Adopta el ADR 0005 del catálogo; lo que cambia está en el ADR 0006 de turnos.
 * <ul>
 * <li><strong>En Java y no con {@code spring.kafka.*}:</strong> el {@code application.yml} de {@code src/test} tapa al
 * principal, y lo que estuviera en el YAML no lo ejercitarían los tests. Con estos beans la autoconfiguración de Spring
 * Boot se retira.</li>
 * <li>{@code String} en la clave y el valor: el mensaje se parsea en el adaptador, y uno roto llega al listener (con
 * {@code JacksonJsonDeserializer}, uno roto traba la partición).</li>
 * <li>{@code earliest}: con un grupo nuevo se procesa lo que haya en el topic; las barreras frenan lo repetido.</li>
 * <li>{@code max.poll.records=1}: el plazo de {@code max.poll.interval.ms} se cuenta por {@code poll}.</li>
 * <li>{@code allow.auto.create.topics=false}: el topic es de la cátedra; con un nombre mal escrito, el consumidor lo
 * crearía.</li>
 * <li>{@link AckMode#RECORD}: el offset se confirma cuando el listener vuelve bien, después de persistir.</li>
 * <li>Un solo hilo: el topic tiene una partición, y así no hay dos eventos del mismo proceso a la vez desde Kafka.</li>
 * </ul>
 */
@Configuration
@EnableKafka
@EnableConfigurationProperties(CatedraKafkaProperties.class)
public class CatedraKafkaConsumerConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(CatedraKafkaConsumerConfiguration.class);

    @Bean
    public ConsumerFactory<String, String> reservationEventConsumerFactory(CatedraKafkaProperties properties) {
        return new DefaultKafkaConsumerFactory<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                properties.getConsumerGroup(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                1,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                Math.toIntExact(properties.getMaxPollInterval().toMillis()),
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,
                false
            )
        );
    }

    /**
     * Para lo inesperado, como la base caída o un choque de versión con un pedido HTTP sobre el mismo proceso. Las fallas
     * esperadas (mensaje mal formado, tipo desconocido, duplicado, proceso inexistente) no llegan acá: vuelven como
     * resultado. Las esperas son las de DPT-22, cada intento queda en el log y al agotarse actúa
     * {@link ReservationEventRecoverer}. El manejador por defecto haría 10 entregas seguidas, sin ninguna espera.
     */
    @Bean
    public CommonErrorHandler reservationEventErrorHandler(CatedraKafkaProperties properties) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new ReservationEventRecoverer(), backOff(properties.getRetryDelays()));
        errorHandler.setRetryListeners(retryListener());
        return errorHandler;
    }

    /** Deja en el log cada intento fallido, con la falla real (contrato §18.4: reintentos observables). */
    static RetryListener retryListener() {
        return (message, failure, attempt) ->
            LOG.warn(
                "Evento de reserva: falló el intento {} (partición {}, offset {}, clave {}): {}",
                attempt,
                message.partition(),
                message.offset(),
                message.key(),
                describe(failure)
            );
    }

    /**
     * La clase y el mensaje de la falla real. Lo que lanza el listener llega envuelto en
     * {@link ListenerExecutionFailedException}: se desenvuelve solo esa. Con {@code AckMode.RECORD}, el {@code commitSync}
     * del offset corre en el mismo {@code try} que el listener, así que una {@code CommitFailedException} llega sin
     * envolver y sin causa (#35 del catálogo): loguear {@code getCause()} daría {@code null}.
     */
    static String describe(Throwable failure) {
        Throwable real = failure instanceof ListenerExecutionFailedException && failure.getCause() != null ? failure.getCause() : failure;
        return real.getMessage() == null ? real.getClass().getName() : real.getClass().getName() + ": " + real.getMessage();
    }

    /** El bean se llama así para que {@code @KafkaListener} lo use sin nombrarlo y la autoconfiguración no cree otro. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
        ConsumerFactory<String, String> consumerFactory,
        CommonErrorHandler errorHandler,
        CatedraKafkaProperties properties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(1);
        factory.setAutoStartup(properties.isListenerEnabled());
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        factory.getContainerProperties().setAuthExceptionRetryInterval(properties.getAuthRetryInterval());
        if (!properties.isListenerEnabled()) {
            LOG.warn("El listener de eventos de reserva está apagado (catedra.kafka.listener-enabled=false)");
        }
        return factory;
    }

    /**
     * Apagado es apagado. Spring 7 pausa los contextos en caché de los tests y los reanuda al reusarlos; por defecto, el
     * registro arranca los contenedores al reanudar aunque tengan {@code autoStartup=false} (visto en el catálogo).
     */
    @Bean
    public SmartInitializingSingleton reservationListenerRespectsAutoStartup(KafkaListenerEndpointRegistry registry) {
        return () -> registry.setAlwaysStartAfterRefresh(false);
    }

    /** Una espera por cada valor de la lista, y después se termina. */
    static BackOff backOff(List<Duration> delays) {
        return () -> {
            Iterator<Duration> remaining = delays.iterator();
            return () -> remaining.hasNext() ? remaining.next().toMillis() : BackOffExecution.STOP;
        };
    }
}
