package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.turnos.reservation.KafkaTestBroker;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneNumber;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PhoneSubmission;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionNotSentException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.PhoneSubmissionUnconfirmedException;
import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * El productor de CU-06 contra brokers reales (Testcontainers), sin contexto de Spring. Además de comprobar el evento
 * publicado, <strong>mide</strong> qué pasa en cada falla con los plazos reales (2 s, 3 s y 5 s del productor; 5 s de la
 * comprobación del topic con el usuario esperando): qué excepción llega y cuánto tarda. Los resultados están en el ADR 0006.
 * <p>
 * Hay un segundo broker con {@code auto.create.topics.enable=false}, como suelen estar los de producción, y es el que se
 * pausa para simular un broker que no confirma: así el compartido no se toca.
 */
@ExtendWith(OutputCaptureExtension.class)
class KafkaPhoneSubmissionPublisherIT {

    private static final String TELEFONO = "+5492615551234";
    private static final KafkaContainer SIN_AUTOCREACION = iniciarSinAutocreacion();

    /** Las comprobaciones creadas: cada una tiene su hilo y, hasta el éxito, su {@code Admin}. */
    private static final List<PhoneTopicCheck> CREADAS = new CopyOnWriteArrayList<>();

    @AfterAll
    static void detener() {
        CREADAS.forEach(PhoneTopicCheck::destroy);
        SIN_AUTOCREACION.stop();
    }

    @Test
    void cu06_6_publicaElEventoDelContratoConLaClaveYEsperaLaConfirmacion(CapturedOutput salida) throws Exception {
        String topic = KafkaTestBroker.createTopic("catedra.turnos.telefono.test-");
        KafkaPhoneSubmissionPublisher productor = productor(KafkaTestBroker.bootstrapServers(), topic);

        productor.publish(envio("phone-submitted-b91e"));

        ConsumerRecord<String, String> publicado = leerUno(KafkaTestBroker.bootstrapServers(), topic);
        assertThat(publicado.key()).isEqualTo("process-a92b47d0");
        // Todos los campos de §15.5, con eventType y schemaVersion exactos y occurredAt en UTC sin fracciones.
        assertThat(publicado.value()).isEqualTo(
            "{\"eventId\":\"phone-submitted-b91e\",\"eventType\":\"AdditionalInformationSubmitted\",\"groupId\":\"proyecto-de-prueba\"," +
                "\"reservationProcessId\":\"process-a92b47d0\",\"holdId\":\"hold-6c0c2f41\",\"requestEventId\":\"request-event-8a31\"," +
                "\"information\":{\"phoneNumber\":\"+5492615551234\"},\"occurredAt\":\"2026-09-10T14:36:00Z\",\"schemaVersion\":1}"
        );
        assertThat(salida.getAll()).contains("AdditionalInformationSubmitted phone-submitted-b91e publicado").doesNotContain("5551234");
    }

    /**
     * La comprobación de fondo del arranque (comprobación manual del #12): cuando terminó bien, el primer envío ya no
     * describe el topic, y el {@code Admin} queda cerrado. El listener del arranque se llama a mano, como lo haría Spring.
     */
    @Test
    void cu06_despuesDelArranqueElPrimerEnvioNoVuelveADescribirElTopic(CapturedOutput salida) throws Exception {
        String topic = KafkaTestBroker.createTopic("catedra.turnos.telefono.test-");
        CatedraKafkaProperties properties = propiedades(KafkaTestBroker.bootstrapServers(), topic);
        PhoneTopicCheck comprobacion = new PhoneTopicCheck(properties);
        KafkaPhoneSubmissionPublisher productor = productor(properties, comprobacion);

        comprobacion.onApplicationReady();
        comprobacion.firstBackgroundAttempt().get(10, TimeUnit.SECONDS);
        assertThat(comprobacion.isChecked()).isTrue();
        assertThat(comprobacion.describeRequests()).isEqualTo(1);

        productor.publish(envio("phone-submitted-arranque"));

        assertThat(comprobacion.describeRequests()).isEqualTo(1);
        assertThat(leerUno(KafkaTestBroker.bootstrapServers(), topic).key()).isEqualTo("process-a92b47d0");
        assertThat(salida.getAll()).contains("Topic de teléfono comprobado (1 pedido(s) al broker)");
    }

    /**
     * Sin la comprobación, publicar en un topic que no existe, en un broker que crea topics solo, <strong>funciona</strong>:
     * el broker crea el topic y confirma. Es el motivo de la comprobación: un nombre mal configurado perdería el teléfono
     * sin ningún error.
     */
    @Test
    void medicion_sinComprobarUnBrokerQueCreaTopicsConfirmaElEnvioAUnTopicMalEscrito() throws Exception {
        String malEscrito = "catedra.turnos.telefono.mal-escrito-" + UUID.randomUUID();
        try (KafkaProducer<String, String> crudo = productorCrudo(KafkaTestBroker.bootstrapServers())) {
            crudo.send(new ProducerRecord<>(malEscrito, "k", "v")).get(10, TimeUnit.SECONDS);
        }

        assertThat(topics(KafkaTestBroker.bootstrapServers())).contains(malEscrito);
    }

    @Test
    void cu06_6a_unTopicQueNoExisteNoSePublicaNiSeCreaAunqueElBrokerCreeTopics(CapturedOutput salida) {
        String malEscrito = "catedra.turnos.telefono.mal-escrito-" + UUID.randomUUID();
        KafkaPhoneSubmissionPublisher productor = productor(KafkaTestBroker.bootstrapServers(), malEscrito);

        assertThatThrownBy(() -> productor.publish(envio("e-1"))).isInstanceOf(PhoneSubmissionNotSentException.class);

        assertThat(topics(KafkaTestBroker.bootstrapServers())).doesNotContain(malEscrito);
        assertThat(salida.getAll()).contains("El topic de teléfono configurado no existe en el broker");
    }

    /** Medido: sin comprobación y sin autocreación, {@code send} bloquea {@code max.block.ms} y falla con un timeout de Kafka. */
    @Test
    void medicion_sinComprobarYSinAutocreacionElEnvioEsperaMaxBlockYFalla() {
        String inexistente = "catedra.turnos.telefono.inexistente-" + UUID.randomUUID();
        long inicio = System.nanoTime();
        try (KafkaProducer<String, String> crudo = productorCrudo(SIN_AUTOCREACION.getBootstrapServers())) {
            assertThatThrownBy(() -> crudo.send(new ProducerRecord<>(inexistente, "k", "v")).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(org.apache.kafka.common.errors.TimeoutException.class)
                .hasMessageContaining("not present in metadata after 2000 ms");
        }
        assertThat(milisegundosDesde(inicio)).isBetween(1_900L, 4_000L);
        assertThat(topics(SIN_AUTOCREACION.getBootstrapServers())).doesNotContain(inexistente);
    }

    @Test
    void cu06_6a_conElBrokerCaidoNoSePublicaYSeRespondeEnElPlazoDeLaComprobacion() throws Exception {
        int puertoCerrado;
        try (ServerSocket socket = new ServerSocket(0)) {
            puertoCerrado = socket.getLocalPort();
        }
        KafkaPhoneSubmissionPublisher productor = productor("127.0.0.1:" + puertoCerrado, "catedra.turnos.telefono.de-prueba");

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> productor.publish(envio("e-1"))).isInstanceOf(PhoneSubmissionNotSentException.class);
        long primera = milisegundosDesde(inicio);

        // Solo se recuerda el éxito: la segunda vez se vuelve a esperar (el mismo pedido, que sigue en vuelo, o uno nuevo).
        inicio = System.nanoTime();
        assertThatThrownBy(() -> productor.publish(envio("e-2"))).isInstanceOf(PhoneSubmissionNotSentException.class);
        assertThat(primera).isBetween(4_900L, 7_000L);
        assertThat(milisegundosDesde(inicio)).isBetween(4_900L, 7_000L);
    }

    /**
     * El topic existe y la comprobación pasó, pero el broker deja de responder (pausado): el envío espera
     * {@code delivery.timeout.ms} (5 s) y llega como resultado desconocido, siempre antes del plazo del futuro (8 s). Ni el
     * template ni el adaptador escriben el teléfono en el log.
     */
    @Test
    void cu06_6a_siElBrokerNoConfirmaATiempoEsUnResultadoDesconocidoAntesDelPlazoDelFuturo(CapturedOutput salida) throws Exception {
        String topic = "catedra.turnos.telefono.pausado-" + UUID.randomUUID();
        crearTopic(SIN_AUTOCREACION.getBootstrapServers(), topic);
        KafkaPhoneSubmissionPublisher productor = productor(SIN_AUTOCREACION.getBootstrapServers(), topic);
        productor.publish(envio("e-antes"));

        String id = SIN_AUTOCREACION.getContainerId();
        DockerClientFactory.instance().client().pauseContainerCmd(id).exec();
        long inicio = System.nanoTime();
        try {
            assertThatThrownBy(() -> productor.publish(envio("e-sin-ack"))).isInstanceOf(PhoneSubmissionUnconfirmedException.class);
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(id).exec();
        }
        long milisegundos = milisegundosDesde(inicio);
        assertThat(milisegundos).isBetween(4_500L, 7_900L);
        assertThat(salida.getAll())
            .contains("AdditionalInformationSubmitted e-sin-ack sin confirmación del broker")
            .contains("org.apache.kafka.common.errors.TimeoutException")
            .doesNotContain("5551234");
        System.out.println("MEDIDO broker pausado: resultado desconocido a los " + milisegundos + " ms");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ayudas
    // ---------------------------------------------------------------------------------------------------------------

    private static PhoneSubmission envio(String eventId) {
        return new PhoneSubmission(
            eventId,
            "process-a92b47d0",
            "hold-6c0c2f41",
            "request-event-8a31",
            PhoneNumber.of(TELEFONO),
            Instant.parse("2026-09-10T14:36:00.123456Z")
        );
    }

    /** El adaptador con los beans y los plazos reales, sin Spring y sin la comprobación de fondo. */
    private static KafkaPhoneSubmissionPublisher productor(String bootstrap, String topic) {
        CatedraKafkaProperties properties = propiedades(bootstrap, topic);
        return productor(properties, new PhoneTopicCheck(properties));
    }

    private static KafkaPhoneSubmissionPublisher productor(CatedraKafkaProperties properties, PhoneTopicCheck comprobacion) {
        CatedraKafkaProducerConfiguration configuracion = new CatedraKafkaProducerConfiguration();
        CREADAS.add(comprobacion);
        return new KafkaPhoneSubmissionPublisher(
            configuracion.phoneKafkaTemplate(configuracion.phoneProducerFactory(properties)),
            properties,
            comprobacion
        );
    }

    private static CatedraKafkaProperties propiedades(String bootstrap, String topic) {
        CatedraKafkaProperties properties = new CatedraKafkaProperties();
        properties.setBootstrapServers(bootstrap);
        properties.setPhoneTopic(topic);
        properties.setGroupId("proyecto-de-prueba");
        return properties;
    }

    private static KafkaProducer<String, String> productorCrudo(String bootstrap) {
        return new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap, ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000),
            new StringSerializer(),
            new StringSerializer()
        );
    }

    private static ConsumerRecord<String, String> leerUno(String bootstrap, String topic) {
        try (
            KafkaConsumer<String, String> consumidor = new KafkaConsumer<>(
                Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    bootstrap,
                    ConsumerConfig.GROUP_ID_CONFIG,
                    "lector-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                    "earliest"
                ),
                new StringDeserializer(),
                new StringDeserializer()
            )
        ) {
            consumidor.subscribe(List.of(topic));
            long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < limite) {
                ConsumerRecords<String, String> registros = consumidor.poll(Duration.ofMillis(500));
                if (!registros.isEmpty()) {
                    assertThat(registros.count()).isEqualTo(1);
                    return registros.iterator().next();
                }
            }
        }
        throw new AssertionError("No llegó ningún mensaje al topic");
    }

    private static List<String> topics(String bootstrap) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            return List.copyOf(admin.listTopics().names().get(10, TimeUnit.SECONDS));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void crearTopic(String bootstrap, String topic) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    private static long milisegundosDesde(long inicio) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
    }

    private static KafkaContainer iniciarSinAutocreacion() {
        KafkaContainer container = new KafkaContainer("apache/kafka-native:4.1.0").withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
        container.start();
        return container;
    }
}
