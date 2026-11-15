package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Comprueba que el topic de teléfono existe ({@code Admin.describeTopics}), una sola vez con éxito por arranque. Sin esto,
 * un nombre mal configurado en un broker que crea topics solo crearía uno nuevo y el teléfono se perdería sin ningún
 * error (medido con Testcontainers, ADR 0006).
 * <p>
 * <strong>Dos caminos, una sola comprobación.</strong>
 * <ul>
 * <li><strong>De fondo</strong>, al terminar el arranque ({@link ApplicationReadyEvent}), con plazo de 15 s. Si falla,
 * se reintenta cada 30 s hasta que salga bien, sin límite: un arranque con ZeroTier caído no puede dejar al primer
 * teléfono pagando la conexión en frío. Nunca bloquea ni impide el arranque: el listener solo programa la tarea y vuelve.
 * Una advertencia por la primera falla y después DEBUG, para no llenar el log.</li>
 * <li><strong>Antes de un envío</strong>, mientras la de fondo no salió bien, con el plazo del usuario esperando (5 s).
 * </ul>
 * La comprobación manual del #12 lo mostró: con un {@code Admin} recién creado y por ZeroTier, describir el topic tardó de
 * 1,9 a 5,1 s, y una vez no respondió en 15 s; ya conectado, de 0,5 a 1,8 s. El primer teléfono después de cada arranque
 * fallaba siempre con el plazo de 2 s que tenía el envío.
 * <p>
 * <strong>Que no se pisen:</strong> hay a lo sumo un pedido {@code describeTopics} en vuelo, y cada camino lo espera
 * <strong>fuera del lock</strong> con su propio plazo. Un envío nunca espera que termine la de fondo (15 s): espera el
 * mismo pedido 5 s. Si el pedido termina bien cuando nadie lo esperaba (el envío ya se rindió), el próximo que pasa lo da
 * por comprobado sin pedir otro. Solo se recuerda el éxito; con el éxito se cierra el {@code Admin}, que no se vuelve a
 * usar.
 * <p>
 * <strong>Un hilo propio y no el {@code taskExecutor}</strong> (que usa el arranque del catálogo): los reintentos
 * necesitan una espera programada, que ese executor no tiene, y el {@code taskScheduler} de {@code @EnableScheduling} no
 * existe en los perfiles de test. Además, en los tests el {@code taskExecutor} es sincrónico, y no se podría probar que el
 * arranque no espera la comprobación.
 */
@Component
public class PhoneTopicCheck implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(PhoneTopicCheck.class);

    /** Programa una tarea. En producción, un hilo propio; en los tests unitarios, uno que la guarda y la corre a mano. */
    interface Scheduler {
        void schedule(Runnable task, Duration delay);

        void shutdown();
    }

    private final CatedraKafkaProperties properties;
    private final Supplier<Admin> adminFactory;
    private final Scheduler scheduler;
    private final AtomicInteger describeRequests = new AtomicInteger();
    private final CompletableFuture<Void> firstBackgroundAttempt = new CompletableFuture<>();

    /** Protegidos por {@code this}. */
    private Admin admin;

    private KafkaFuture<Map<String, TopicDescription>> inFlight;

    private volatile boolean checked;

    /** Solo los toca el hilo de la comprobación de fondo. */
    private int backgroundFailures;

    @Autowired
    public PhoneTopicCheck(CatedraKafkaProperties properties) {
        this(properties, () -> createAdmin(properties), ownThread());
    }

    PhoneTopicCheck(CatedraKafkaProperties properties, Supplier<Admin> adminFactory, Scheduler scheduler) {
        this.properties = properties;
        this.adminFactory = adminFactory;
        this.scheduler = scheduler;
    }

    /**
     * Comprueba el topic si todavía no se comprobó, esperando a lo sumo {@code timeout}.
     *
     * @throws PhoneTopicCheckException si el topic no existe o no se pudo comprobar a tiempo
     */
    void ensureChecked(Duration timeout) {
        if (checked) {
            return;
        }
        KafkaFuture<Map<String, TopicDescription>> request;
        synchronized (this) {
            if (checked) {
                return;
            }
            if (inFlight != null && inFlight.isDone() && !inFlight.isCompletedExceptionally()) {
                // Terminó bien cuando nadie lo esperaba.
                markChecked();
                return;
            }
            if (inFlight == null || inFlight.isCompletedExceptionally()) {
                inFlight = startRequest();
            }
            request = inFlight;
        }
        try {
            request.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new PhoneTopicCheckException(
                cause instanceof UnknownTopicOrPartitionException,
                KafkaPhoneSubmissionPublisher.describe(cause),
                cause
            );
        } catch (TimeoutException e) {
            throw new PhoneTopicCheckException(false, "sin respuesta en " + timeout.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhoneTopicCheckException(false, "se interrumpió la espera", e);
        }
        synchronized (this) {
            markChecked();
        }
    }

    /** Con el lock tomado. */
    private KafkaFuture<Map<String, TopicDescription>> startRequest() {
        try {
            if (admin == null) {
                admin = adminFactory.get();
            }
            describeRequests.incrementAndGet();
            return admin.describeTopics(List.of(properties.getPhoneTopic())).allTopicNames();
        } catch (RuntimeException e) {
            throw new PhoneTopicCheckException(false, KafkaPhoneSubmissionPublisher.describe(e), e);
        }
    }

    /** Con el lock tomado. */
    private void markChecked() {
        if (checked) {
            return;
        }
        checked = true;
        inFlight = null;
        LOG.info("Topic de teléfono comprobado ({} pedido(s) al broker)", describeRequests.get());
        closeAdmin();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.getProducer().isBackgroundTopicCheckEnabled()) {
            LOG.info("La comprobación de fondo del topic de teléfono está desactivada");
            return;
        }
        scheduleBackgroundAttempt(Duration.ZERO);
    }

    void backgroundAttempt() {
        try {
            if (checked) {
                return;
            }
            try {
                ensureChecked(properties.getProducer().getBackgroundTopicCheckTimeout());
            } catch (PhoneTopicCheckException e) {
                logBackgroundFailure(e);
                scheduleBackgroundAttempt(properties.getProducer().getBackgroundTopicCheckInterval());
            } catch (RuntimeException e) {
                // Nunca se corta la cadena de reintentos por una falla inesperada.
                logBackgroundFailure(new PhoneTopicCheckException(false, KafkaPhoneSubmissionPublisher.describe(e), e));
                scheduleBackgroundAttempt(properties.getProducer().getBackgroundTopicCheckInterval());
            }
        } finally {
            firstBackgroundAttempt.complete(null);
        }
    }

    private void scheduleBackgroundAttempt(Duration delay) {
        try {
            scheduler.schedule(this::backgroundAttempt, delay);
        } catch (RuntimeException e) {
            // El hilo ya se apagó (la aplicación se está cerrando): no hay nada que reintentar.
            LOG.debug("No se programó la comprobación de fondo del topic de teléfono: {}", e.toString());
        }
    }

    private void logBackgroundFailure(PhoneTopicCheckException failure) {
        backgroundFailures++;
        long interval = properties.getProducer().getBackgroundTopicCheckInterval().toSeconds();
        if (backgroundFailures > 1) {
            LOG.debug("Comprobación de fondo del topic de teléfono, intento {}: {}; se reintenta en {} s", backgroundFailures, failure.reason(), interval);
        } else if (failure.topicMissing()) {
            LOG.error(
                "El topic de teléfono configurado no existe en el broker; revisar CATEDRA_KAFKA_PHONE_TOPIC (comprobación de fondo, se reintenta cada {} s)",
                interval
            );
        } else {
            LOG.warn(
                "No se pudo comprobar el topic de teléfono en segundo plano ({}); se reintenta cada {} s sin bloquear nada, y cada envío lo comprueba mientras tanto",
                failure.reason(),
                interval
            );
        }
    }

    boolean isChecked() {
        return checked;
    }

    /** Cuántos pedidos {@code describeTopics} se hicieron: va en el log del éxito y lo miran los tests. */
    int describeRequests() {
        return describeRequests.get();
    }

    /** Se completa cuando termina el primer intento de fondo, bien o mal: la señal que esperan los tests. */
    CompletableFuture<Void> firstBackgroundAttempt() {
        return firstBackgroundAttempt;
    }

    private void closeAdmin() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(1));
            admin = null;
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
        synchronized (this) {
            closeAdmin();
        }
    }

    /**
     * El cliente de administración, creado con la primera comprobación. El plazo de cada pedido es el mayor de los dos
     * (15 s): el pedido es uno solo y cada camino lo espera con el suyo.
     */
    private static Admin createAdmin(CatedraKafkaProperties properties) {
        CatedraKafkaProperties.Producer producer = properties.getProducer();
        Duration longest = producer.getBackgroundTopicCheckTimeout().compareTo(producer.getTopicCheckTimeout()) > 0
            ? producer.getBackgroundTopicCheckTimeout()
            : producer.getTopicCheckTimeout();
        int timeout = Math.toIntExact(longest.toMillis());
        return Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                timeout,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                timeout
            )
        );
    }

    /** Un hilo propio, de tipo daemon: nunca impide que la JVM termine. */
    private static Scheduler ownThread() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "phone-topic-check");
            thread.setDaemon(true);
            return thread;
        });
        return new Scheduler() {
            @Override
            public void schedule(Runnable task, Duration delay) {
                executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
            }

            @Override
            public void shutdown() {
                executor.shutdownNow();
            }
        };
    }
}
