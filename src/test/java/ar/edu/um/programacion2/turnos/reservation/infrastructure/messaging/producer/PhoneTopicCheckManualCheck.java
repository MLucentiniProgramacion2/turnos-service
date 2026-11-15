package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.opentest4j.AssertionFailedError;
import org.slf4j.LoggerFactory;

/**
 * Comprobación manual: <strong>cuánto tardan en frío, contra el broker real, la comprobación del topic de teléfono y el
 * productor</strong>, como en el primer envío después de arrancar (comprobación manual del #12: la comprobación venció a
 * los 2 s por ZeroTier). <strong>Nunca corre en el CI</strong>: el nombre no coincide con los patrones de surefire ({@code *Test}) ni
 * de failsafe ({@code *IT*}) y, además, exige {@code -Dcatedra.manual=true}.
 * <p>
 * <strong>Solo describe el topic real</strong> ({@code CATEDRA_KAFKA_PHONE_TOPIC}): no crea topics, no publica, no prueba
 * otros nombres y no usa ningún consumer group. Con ZeroTier conectado ({@code sudo zerotier-cli listnetworks}) y
 * {@code CATEDRA_KAFKA_BOOTSTRAP_SERVERS} y {@code CATEDRA_KAFKA_PHONE_TOPIC} en el {@code .env} (una variable de entorno con
 * el mismo nombre tiene prioridad):
 *
 * <pre>./mvnw test -Dtest=PhoneTopicCheckManualCheck -Dcatedra.manual=true [-Dintentos=5 -Dplazo=15]</pre>
 *
 * Para correr una sola medición: {@code -Dtest='PhoneTopicCheckManualCheck#mideElAdmin*'} o
 * {@code -Dtest='PhoneTopicCheckManualCheck#mideElProductor*'}. El plazo es amplio (15 s) para ver el tiempo real, no el
 * corte. Lleva su propio {@link Timeout}: el global de los tests ({@code junit-platform.properties}) es de 15 s por método,
 * y cortaba la medición del {@code Admin} después del tercer intento.
 * <ul>
 * <li><strong>{@code Admin}:</strong> por cada intento crea uno nuevo, describe el topic en frío (lo que paga hoy el primer
 * envío) y otra vez con el mismo cliente, ya conectado, y lo cierra.</li>
 * <li><strong>Productor:</strong> por cada intento crea un {@code KafkaProducer} nuevo con la configuración real del
 * productor de teléfono ({@link CatedraKafkaProducerConfiguration}), salvo {@code max.block.ms} con el plazo amplio, y pide
 * {@code partitionsFor} del topic: el mismo recorrido que hace {@code send} antes de poder enviar (bootstrap y metadatos
 * del topic), <strong>sin publicar nada</strong>. Antes, con un {@code Admin} aparte, confirma que el topic existe: si no
 * se puede confirmar, no crea ningún productor (pedir metadatos de un topic inexistente a un broker que crea topics solo
 * lo crearía).</li>
 * </ul>
 * <p>
 * <strong>Nunca imprime la dirección del broker ni el nombre del topic</strong>: el log de Kafka se apaga (sus advertencias
 * de conexión llevan la IP) y los mensajes de error se imprimen con los dos valores tapados.
 */
@EnabledIfSystemProperty(named = "catedra.manual", matches = "true")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class PhoneTopicCheckManualCheck {

    private String bootstrap;
    private String topic;
    private int intentos;
    private Duration plazo;

    @BeforeEach
    void leerConfiguracion() throws IOException {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.apache.kafka")).setLevel(Level.OFF);
        Properties dotEnv = readDotEnv();
        bootstrap = required("CATEDRA_KAFKA_BOOTSTRAP_SERVERS", dotEnv);
        topic = required("CATEDRA_KAFKA_PHONE_TOPIC", dotEnv);
        intentos = Integer.parseInt(System.getProperty("intentos", "5"));
        plazo = Duration.ofSeconds(Long.parseLong(System.getProperty("plazo", "15")));
    }

    @Test
    void mideElAdminEnFrio() throws InterruptedException {
        System.out.println("admin: intentos=" + intentos + " plazo=" + plazo.toSeconds() + " s (un Admin nuevo por intento)");
        for (int i = 1; i <= intentos; i++) {
            long inicio = System.nanoTime();
            try (Admin admin = Admin.create(config(bootstrap, plazo))) {
                long creado = System.nanoTime();
                String frio = describir(admin, topic, plazo, bootstrap);
                long enFrio = System.nanoTime();
                String caliente = describir(admin, topic, plazo, bootstrap);
                long enCaliente = System.nanoTime();
                System.out.printf(
                    "admin intento %d: crear %d ms | describir en frío %d ms (%s) | otra vez, conectado, %d ms (%s)%n",
                    i,
                    ms(inicio, creado),
                    ms(creado, enFrio),
                    frio,
                    ms(enFrio, enCaliente),
                    caliente
                );
            }
        }
    }

    @Test
    void mideElProductorEnFrio() throws InterruptedException {
        System.out.println("productor: intentos=" + intentos + " max.block=" + plazo.toSeconds() + " s (un KafkaProducer nuevo por intento, sin publicar)");
        try (Admin admin = Admin.create(config(bootstrap, plazo))) {
            String existe = describir(admin, topic, plazo, bootstrap);
            if (!existe.startsWith("ok")) {
                System.out.println("productor: no se mide, no se pudo confirmar que el topic existe (" + existe + ")");
                throw new AssertionFailedError("No se pudo confirmar que el topic existe");
            }
        }
        Map<String, Object> configuracion = configuracionDelProductor();
        for (int i = 1; i <= intentos; i++) {
            long inicio = System.nanoTime();
            try (KafkaProducer<String, String> productor = new KafkaProducer<>(configuracion)) {
                long creado = System.nanoTime();
                String resultado;
                try {
                    List<PartitionInfo> particiones = productor.partitionsFor(topic);
                    resultado = "ok, " + particiones.size() + " particiones";
                } catch (KafkaException e) {
                    resultado = "FALLÓ " + e.getClass().getSimpleName() + ": " + tapar(e.getMessage(), topic, bootstrap);
                }
                long metadatos = System.nanoTime();
                productor.close(Duration.ofSeconds(1));
                System.out.printf(
                    "productor intento %d: crear %d ms | partitionsFor en frío %d ms (%s)%n",
                    i,
                    ms(inicio, creado),
                    ms(creado, metadatos),
                    resultado
                );
            }
        }
    }

    /** La configuración real del productor de teléfono, salvo {@code max.block.ms}, que lleva el plazo amplio. */
    private Map<String, Object> configuracionDelProductor() {
        CatedraKafkaProperties properties = new CatedraKafkaProperties();
        properties.setBootstrapServers(bootstrap);
        properties.getProducer().setMaxBlock(plazo);
        Map<String, Object> configuracion = new HashMap<>(
            new CatedraKafkaProducerConfiguration().phoneProducerFactory(properties).getConfigurationProperties()
        );
        if (!Long.valueOf(plazo.toMillis()).equals(configuracion.get(ProducerConfig.MAX_BLOCK_MS_CONFIG))) {
            throw new IllegalStateException("La configuración del productor no tomó el plazo amplio");
        }
        return configuracion;
    }

    /** Los mismos tres valores que arma el adaptador, con el plazo amplio en vez de los 2 s. */
    private static Map<String, Object> config(String bootstrap, Duration plazo) {
        int timeout = Math.toIntExact(plazo.toMillis());
        return Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrap,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
            timeout,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
            timeout
        );
    }

    /** "ok, N particiones" o la falla en una línea, sin la dirección ni el nombre del topic. */
    private static String describir(Admin admin, String topic, Duration plazo, String bootstrap) throws InterruptedException {
        try {
            TopicDescription descripcion = admin
                .describeTopics(List.of(topic))
                .allTopicNames()
                .get(plazo.toMillis() + 1000, TimeUnit.MILLISECONDS)
                .get(topic);
            return "ok, " + descripcion.partitions().size() + " particiones";
        } catch (ExecutionException e) {
            Throwable causa = e.getCause() != null ? e.getCause() : e;
            return "FALLÓ " + causa.getClass().getSimpleName() + ": " + tapar(causa.getMessage(), topic, bootstrap);
        } catch (TimeoutException e) {
            return "FALLÓ sin respuesta en el plazo";
        }
    }

    /** Tapa el nombre del topic, la dirección del broker y cualquier IPv4 que traiga un mensaje de Kafka. */
    static String tapar(String mensaje, String topic, String bootstrap) {
        if (mensaje == null) {
            return "(sin mensaje)";
        }
        String tapado = mensaje.replace(topic, "<topic>");
        for (String servidor : bootstrap.split(",")) {
            if (!servidor.isBlank()) {
                tapado = tapado.replace(servidor.strip(), "<broker>");
            }
        }
        return tapado.replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b", "<ip>");
    }

    private static long ms(long desde, long hasta) {
        return TimeUnit.NANOSECONDS.toMillis(hasta - desde);
    }

    private static Properties readDotEnv() throws IOException {
        Properties dotEnv = new Properties();
        Path path = Path.of(".env");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                dotEnv.load(reader);
            }
        }
        return dotEnv;
    }

    private static String required(String name, Properties dotEnv) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = dotEnv.getProperty(name);
        }
        if (value == null || value.isBlank()) {
            System.out.println("FALLÓ Falta " + name + " en el .env o en el entorno");
            AssertionFailedError failure = new AssertionFailedError("Falta " + name + " en el .env o en el entorno");
            failure.setStackTrace(new StackTraceElement[0]);
            throw failure;
        }
        return value.strip();
    }
}
