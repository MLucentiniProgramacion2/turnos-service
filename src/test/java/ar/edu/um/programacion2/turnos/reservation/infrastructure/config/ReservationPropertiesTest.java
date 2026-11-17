package ar.edu.um.programacion2.turnos.reservation.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ar.edu.um.programacion2.turnos.reservation.application.usecases.EventTransactions;
import ar.edu.um.programacion2.turnos.reservation.application.usecases.ReadTransactions;
import ar.edu.um.programacion2.turnos.reservation.domain.model.AvailabilityPolicy;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/** Las reglas de fechas (DPT-10) se validan al arrancar: una zona o un horizonte inválidos no levantan la aplicación. */
class ReservationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ReservationConfiguration.class)
        .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));

    @Test
    void sinConfigurarNadaValenLosValoresDeDpt10() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AvailabilityPolicy.class)).isEqualTo(new AvailabilityPolicy(ZoneId.of("America/Argentina/Mendoza"), 60));
        });
    }

    @Test
    void laZonaYElHorizonteSonConfigurables() {
        runner
            .withPropertyValues("reservation.time-zone=America/Argentina/Buenos_Aires", "reservation.booking-horizon-days=30")
            .run(context ->
                assertThat(context.getBean(AvailabilityPolicy.class)).isEqualTo(new AvailabilityPolicy(ZoneId.of("America/Argentina/Buenos_Aires"), 30))
            );
    }

    /** Es lo que hace {@code application.yml} con las variables de entorno ausentes. */
    @Test
    void conLasVariablesDeEntornoAusentesValenLosValoresPorDefecto() {
        runner
            .withPropertyValues(
                "reservation.time-zone=${RESERVATION_TIME_ZONE_QUE_NO_EXISTE:America/Argentina/Mendoza}",
                "reservation.booking-horizon-days=${RESERVATION_BOOKING_HORIZON_DAYS_QUE_NO_EXISTE:60}"
            )
            .run(context ->
                assertThat(context.getBean(AvailabilityPolicy.class)).isEqualTo(new AvailabilityPolicy(ZoneId.of("America/Argentina/Mendoza"), 60))
            );
    }

    @ParameterizedTest
    @ValueSource(strings = { "Mendoza", "America/Mendosa", "-25:00" })
    void conUnaZonaQueNoExisteNoArranca(String zona) {
        runner
            .withPropertyValues("reservation.time-zone=" + zona)
            .run(context ->
                assertThat(context.getStartupFailure()).isNotNull().hasRootCauseInstanceOf(java.time.DateTimeException.class).isInstanceOfAny(
                    ConfigurationPropertiesBindException.class,
                    org.springframework.beans.factory.BeanCreationException.class
                )
            );
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "366" })
    void conUnHorizonteFueraDeRangoNoArranca(String dias) {
        runner
            .withPropertyValues("reservation.booking-horizon-days=" + dias)
            .run(context -> assertThat(context.getStartupFailure()).isNotNull().hasStackTraceContaining("reservation.booking-horizon-days"));
    }

    @Test
    void elRelojDeLaReservaEsUtc() {
        runner.run(context -> assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC));
    }

    @Test
    void lasTransaccionesDeLosEventosTienenUnPlazoDe10SegundosPorDefecto() {
        runner.run(context -> assertThat(context.getBean(EventTransactions.class).template().getTimeout()).isEqualTo(10));
    }

    @ParameterizedTest
    @ValueSource(strings = { "0s", "500ms", "61s" })
    void conUnPlazoDeTransaccionDeEventosFueraDeRangoNoArranca(String plazo) {
        runner
            .withPropertyValues("reservation.event-transaction-timeout=" + plazo)
            .run(context -> assertThat(context.getStartupFailure()).isNotNull().hasStackTraceContaining("reservation.event-transaction-timeout"));
    }

    @Test
    void lasTransaccionesDeLosEventosNoReemplazanAlTransactionTemplateDeSpringBoot() {
        // Si EventTransactions fuera un TransactionTemplate, la autoconfiguración de Spring Boot no crearía el suyo.
        runner.run(context -> assertThat(context.getBeansOfType(org.springframework.transaction.support.TransactionOperations.class)).isEmpty());
    }

    @Test
    void lasTransaccionesDeLasConsultasSonDeSoloLecturaYRepeatableRead() {
        // CU-09 y CU-12 leen la página y el total en la misma foto. Tampoco es un TransactionTemplate (test de arriba).
        runner.run(context -> {
            ReadTransactions consultas = context.getBean(ReadTransactions.class);
            assertThat(consultas.template().isReadOnly()).isTrue();
            assertThat(consultas.template().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        });
    }
}
