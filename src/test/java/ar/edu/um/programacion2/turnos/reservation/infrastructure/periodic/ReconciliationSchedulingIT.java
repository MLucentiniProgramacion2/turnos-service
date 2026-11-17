package ar.edu.um.programacion2.turnos.reservation.infrastructure.periodic;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import ar.edu.um.programacion2.turnos.reservation.KafkaTestBroker;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationReport;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.in.ReconcilePendingProcessesUseCase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ConsumerStartedEvent;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * El disparador de CU-11 con el contexto entero, la reconciliación prendida y el listener de Kafka prendido (contra el
 * broker de Testcontainers). El caso de uso es un doble que <strong>no termina</strong> hasta que el test lo suelta: si el
 * arranque esperara la primera ejecución, el contexto nunca terminaría de levantar y este test no llegaría a correr.
 * <p>
 * Además <strong>mide</strong> (opción B del plan del #14) en qué hilo corre la primera ejecución y cuándo, respecto de
 * {@code ApplicationReadyEvent} y del arranque del consumidor de Kafka. Lo deja en la salida del test.
 */
@IntegrationTest
@Import(ReconciliationSchedulingIT.Doble.class)
@TestPropertySource(properties = { "reservation.reconciliation.enabled=true", "catedra.kafka.listener-enabled=true" })
class ReconciliationSchedulingIT {

    private static final String ACCIONES = KafkaTestBroker.createTopic("alumnos.turnos.acciones.reconciliacion-");
    private static final CountDownLatch PRIMERA_EMPEZO = new CountDownLatch(1);
    private static final CountDownLatch SOLTAR = new CountDownLatch(1);
    private static final AtomicInteger LLAMADOS = new AtomicInteger();
    private static final AtomicReference<String> HILO = new AtomicReference<>();
    private static final AtomicLong EMPEZO = new AtomicLong();
    private static final AtomicLong LISTA = new AtomicLong();
    private static final AtomicLong CONSUMIDOR = new AtomicLong();

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry properties) {
        properties.add("catedra.kafka.bootstrap-servers", KafkaTestBroker::bootstrapServers);
        properties.add("catedra.kafka.actions-topic", () -> ACCIONES);
        properties.add("catedra.kafka.consumer-group", () -> "alumnos-reconciliacion-turnos");
    }

    @AfterAll
    static void soltar() {
        SOLTAR.countDown();
    }

    /** El caso de uso de test (reemplaza al real) y las marcas de tiempo del arranque. */
    @TestConfiguration
    static class Doble {

        @Bean
        @Primary
        ReconcilePendingProcessesUseCase reconciliacionQueRetiene() {
            return () -> {
                LLAMADOS.incrementAndGet();
                if (EMPEZO.compareAndSet(0, System.nanoTime())) {
                    HILO.set(Thread.currentThread().getName());
                    PRIMERA_EMPEZO.countDown();
                    try {
                        SOLTAR.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new ReconciliationReport.Skipped();
            };
        }

        @Bean
        MarcasDeTiempo marcasDeTiempo() {
            return new MarcasDeTiempo();
        }
    }

    static class MarcasDeTiempo {

        @EventListener(ApplicationReadyEvent.class)
        void lista() {
            LISTA.compareAndSet(0, System.nanoTime());
        }

        @EventListener(ConsumerStartedEvent.class)
        void consumidor() {
            CONSUMIDOR.compareAndSet(0, System.nanoTime());
        }
    }

    @Test
    void laPrimeraEjecucionCorreAlArrancarEnElHiloDelSchedulerSinDemorarElArranque() throws Exception {
        assertThat(PRIMERA_EMPEZO.await(30, TimeUnit.SECONDS)).as("la primera ejecución corrió sola, sin que nadie la llame").isTrue();

        // El contexto está listo aunque la primera ejecución no terminó: no la esperó.
        assertThat(LISTA.get()).as("la aplicación quedó lista").isPositive();
        assertThat(HILO.get()).startsWith("turnos-service-scheduling-");
        // fixedDelay: mientras la primera no termina, no empieza otra (la de arranque y la periódica no se superponen).
        assertThat(LLAMADOS.get()).isEqualTo(1);
        System.out.printf(
            "%nMedición del disparador (#14): hilo=%s; primera ejecución %+d ms respecto de ApplicationReadyEvent; consumidor de Kafka %s%n",
            HILO.get(),
            TimeUnit.NANOSECONDS.toMillis(EMPEZO.get() - LISTA.get()),
            CONSUMIDOR.get() == 0
                ? "todavía no arrancó"
                : "%+d ms respecto de ApplicationReadyEvent".formatted(TimeUnit.NANOSECONDS.toMillis(CONSUMIDOR.get() - LISTA.get()))
        );
    }
}
