package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.turnos.reservation.infrastructure.config.CatedraKafkaProperties;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * La comprobación del topic de teléfono sin broker: un {@code Admin} simulado cuyos pedidos se terminan a mano y un
 * planificador que guarda las tareas con su demora, para recorrer los reintentos sin esperar.
 */
@ExtendWith(OutputCaptureExtension.class)
class PhoneTopicCheckTest {

    private static final String WARN_DE_FONDO = "No se pudo comprobar el topic de teléfono en segundo plano";

    private final CatedraKafkaProperties properties = propiedades();
    private final Admin admin = mock(Admin.class);
    private final AtomicInteger adminsCreados = new AtomicInteger();
    /** Los pedidos, en orden; los agrega el hilo que llama a {@code describeTopics}. */
    private final LinkedBlockingDeque<KafkaFutureImpl<Map<String, TopicDescription>>> pedidos = new LinkedBlockingDeque<>();
    /** Un permiso por pedido hecho: el test espera los pedidos sin dormir. */
    private final Semaphore pedidosHechos = new Semaphore(0);
    private final PlanificadorManual planificador = new PlanificadorManual();
    private final PhoneTopicCheck comprobacion = new PhoneTopicCheck(
        properties,
        () -> {
            adminsCreados.incrementAndGet();
            return admin;
        },
        planificador
    );

    /** Cada {@code describeTopics} devuelve un pedido nuevo, sin terminar: el test decide cómo termina. */
    private void cadaPedidoQuedaPendiente() {
        when(admin.describeTopics(anyCollection())).thenAnswer(invocation -> {
            KafkaFutureImpl<Map<String, TopicDescription>> pedido = new KafkaFutureImpl<>();
            pedidos.addLast(pedido);
            DescribeTopicsResult resultado = mock(DescribeTopicsResult.class);
            when(resultado.allTopicNames()).thenReturn(pedido);
            pedidosHechos.release();
            return resultado;
        });
    }

    /** Cada {@code describeTopics} falla enseguida con {@code falla}. */
    private void cadaPedidoFallaCon(RuntimeException falla) {
        when(admin.describeTopics(anyCollection())).thenAnswer(invocation -> {
            KafkaFutureImpl<Map<String, TopicDescription>> pedido = new KafkaFutureImpl<>();
            pedido.completeExceptionally(falla);
            DescribeTopicsResult resultado = mock(DescribeTopicsResult.class);
            when(resultado.allTopicNames()).thenReturn(pedido);
            return resultado;
        });
    }

    @Test
    void elListenerDelArranqueSoloProgramaLaComprobacionYVuelve() {
        cadaPedidoQuedaPendiente();

        comprobacion.onApplicationReady();

        // Nada se pidió todavía: el arranque no espera la comprobación.
        verify(admin, never()).describeTopics(anyCollection());
        assertThat(planificador.demoras()).containsExactly(Duration.ZERO);
        assertThat(comprobacion.firstBackgroundAttempt()).isNotDone();
    }

    @Test
    void desactivadaNoProgramaNada(CapturedOutput salida) {
        properties.getProducer().setBackgroundTopicCheckEnabled(false);

        comprobacion.onApplicationReady();

        assertThat(planificador.demoras()).isEmpty();
        assertThat(salida.getAll()).contains("La comprobación de fondo del topic de teléfono está desactivada");
    }

    @Test
    void siFallaSeReintentaCada30sConUnaSolaAdvertencia(CapturedOutput salida) {
        cadaPedidoFallaCon(new TimeoutException("Timed out waiting for a node assignment. Call: listNodes"));
        comprobacion.onApplicationReady();

        planificador.correrLaSiguiente();
        planificador.correrLaSiguiente();
        planificador.correrLaSiguiente();

        verify(admin, times(3)).describeTopics(anyCollection());
        assertThat(planificador.demoras()).containsExactly(Duration.ZERO, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
        assertThat(salida.getAll().split(WARN_DE_FONDO, -1)).hasSize(2);
        assertThat(salida.getAll()).contains("Timed out waiting for a node assignment").contains("se reintenta cada 30 s");
        assertThat(comprobacion.firstBackgroundAttempt()).isDone();
        assertThat(comprobacion.isChecked()).isFalse();
    }

    @Test
    void siUnEnvioLaLograLaDeFondoParaSinPedirOtraVez(CapturedOutput salida) {
        cadaPedidoQuedaPendiente();
        comprobacion.onApplicationReady();
        // El primer intento de fondo espera su pedido en otro hilo; el pedido falla.
        Thread deFondo = new Thread(planificador::correrLaSiguiente);
        deFondo.start();
        esperarUnPedido();
        pedidos.getLast().completeExceptionally(new TimeoutException("Timed out waiting for a node assignment. Call: listNodes"));
        unir(deFondo);

        // Un envío la comprueba con éxito antes del próximo intento de fondo.
        Thread envio = new Thread(() -> comprobacion.ensureChecked(Duration.ofSeconds(5)));
        envio.start();
        esperarUnPedido();
        pedidos.getLast().complete(Map.of());
        unir(envio);

        planificador.correrLaSiguiente();

        verify(admin, times(2)).describeTopics(anyCollection());
        assertThat(planificador.pendientes()).isZero();
        assertThat(comprobacion.isChecked()).isTrue();
        assertThat(salida.getAll()).contains("Topic de teléfono comprobado (2 pedido(s) al broker)");
        verify(admin).close(any(Duration.class));
    }

    @Test
    void conExitoDeFondoQuedaComprobadaYSeCierraElAdmin(CapturedOutput salida) {
        when(admin.describeTopics(anyCollection())).thenAnswer(invocation -> {
            KafkaFutureImpl<Map<String, TopicDescription>> pedido = new KafkaFutureImpl<>();
            pedido.complete(Map.of());
            DescribeTopicsResult resultado = mock(DescribeTopicsResult.class);
            when(resultado.allTopicNames()).thenReturn(pedido);
            return resultado;
        });
        comprobacion.onApplicationReady();

        planificador.correrLaSiguiente();
        comprobacion.ensureChecked(Duration.ofMillis(100));

        assertThat(comprobacion.isChecked()).isTrue();
        assertThat(planificador.pendientes()).isZero();
        verify(admin, times(1)).describeTopics(anyCollection());
        verify(admin).close(any(Duration.class));
        assertThat(salida.getAll()).contains("Topic de teléfono comprobado (1 pedido(s) al broker)").doesNotContain(WARN_DE_FONDO);
    }

    @Test
    void unPedidoQueTerminaBienCuandoNadieLoEsperabaCuentaComoComprobado() {
        cadaPedidoQuedaPendiente();

        assertThatThrownBy(() -> comprobacion.ensureChecked(Duration.ofMillis(100)))
            .isInstanceOf(PhoneTopicCheckException.class)
            .hasMessage("sin respuesta en 100 ms");
        pedidos.getFirst().complete(Map.of());
        comprobacion.ensureChecked(Duration.ofMillis(100));

        assertThat(comprobacion.isChecked()).isTrue();
        verify(admin, times(1)).describeTopics(anyCollection());
        verify(admin).close(any(Duration.class));
    }

    @Test
    void unTopicInexistenteEsElErrorDeConfiguracion(CapturedOutput salida) {
        cadaPedidoFallaCon(new UnknownTopicOrPartitionException("This server does not host this topic-partition."));

        assertThatThrownBy(() -> comprobacion.ensureChecked(Duration.ofSeconds(1)))
            .isInstanceOfSatisfying(PhoneTopicCheckException.class, falla -> assertThat(falla.topicMissing()).isTrue());

        comprobacion.onApplicationReady();
        planificador.correrLaSiguiente();
        assertThat(salida.getAll()).contains("El topic de teléfono configurado no existe en el broker; revisar CATEDRA_KAFKA_PHONE_TOPIC");
        assertThat(planificador.demoras()).containsExactly(Duration.ZERO, Duration.ofSeconds(30));
    }

    @Test
    void unaFallaInesperadaAlCrearElAdminTambienSeReintenta() {
        PhoneTopicCheck sinAdmin = new PhoneTopicCheck(
            properties,
            () -> {
                throw new IllegalStateException("configuración inválida");
            },
            planificador
        );

        sinAdmin.onApplicationReady();
        planificador.correrLaSiguiente();

        assertThat(planificador.demoras()).containsExactly(Duration.ZERO, Duration.ofSeconds(30));
        assertThat(sinAdmin.firstBackgroundAttempt()).isDone();
    }

    /**
     * Que no se pisen: con la de fondo esperando su pedido (plazo de 15 s), un envío espera el mismo pedido con su plazo y
     * se rinde a tiempo; no queda esperando el lock ni pide otro.
     */
    @Test
    void elEnvioNoEsperaALaDeFondoSinoAlMismoPedidoConSuPlazo() throws Exception {
        cadaPedidoQuedaPendiente();
        Thread deFondo = new Thread(comprobacion::backgroundAttempt);
        deFondo.start();
        esperarUnPedido();

        long inicio = System.nanoTime();
        assertThatThrownBy(() -> comprobacion.ensureChecked(Duration.ofMillis(300))).isInstanceOf(PhoneTopicCheckException.class);
        long milisegundos = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);

        assertThat(milisegundos).isBetween(250L, 2_000L);
        verify(admin, times(1)).describeTopics(anyCollection());
        pedidos.getFirst().complete(Map.of());
        unir(deFondo);
        assertThat(comprobacion.isChecked()).isTrue();
        assertThat(adminsCreados).hasValue(1);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void esperarUnPedido() {
        try {
            assertThat(pedidosHechos.tryAcquire(5, TimeUnit.SECONDS)).as("un pedido describeTopics").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void unir(Thread hilo) {
        try {
            hilo.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        assertThat(hilo.isAlive()).isFalse();
    }

    private static CatedraKafkaProperties propiedades() {
        CatedraKafkaProperties properties = new CatedraKafkaProperties();
        properties.setBootstrapServers("localhost:1");
        properties.setPhoneTopic("topic-de-telefono-de-test");
        return properties;
    }

    /** Guarda las tareas con su demora; el test las corre en orden, en su propio hilo. */
    private static final class PlanificadorManual implements PhoneTopicCheck.Scheduler {

        private final List<Duration> demoras = new ArrayList<>();
        private final Deque<Runnable> tareas = new ArrayDeque<>();

        @Override
        public synchronized void schedule(Runnable task, Duration delay) {
            demoras.add(delay);
            tareas.addLast(task);
        }

        @Override
        public void shutdown() {}

        void correrLaSiguiente() {
            Runnable tarea;
            synchronized (this) {
                tarea = tareas.pollFirst();
            }
            assertThat(tarea).as("una tarea programada").isNotNull();
            tarea.run();
        }

        synchronized List<Duration> demoras() {
            return List.copyOf(demoras);
        }

        synchronized int pendientes() {
            return tareas.size();
        }
    }
}
