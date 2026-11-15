package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Las reglas de fechas de DPT-10, con sus bordes: todos los límites son inclusivos. */
class AvailabilityPolicyTest {

    private static final LocalDate HOY = LocalDate.parse("2026-11-16");
    private static final AvailabilityPolicy POLITICA = new AvailabilityPolicy(ZoneId.of("America/Argentina/Mendoza"), 60);

    static Stream<Arguments> rangos() {
        return Stream.of(
            Arguments.of("un solo día, hoy", HOY, HOY, null),
            Arguments.of("7 días: to = from + 6", HOY, HOY.plusDays(6), null),
            Arguments.of("8 días: to = from + 7", HOY, HOY.plusDays(7), RangeViolation.TOO_LONG),
            Arguments.of("desde ayer", HOY.minusDays(1), HOY, RangeViolation.IN_THE_PAST),
            Arguments.of("termina el día 60", HOY.plusDays(54), HOY.plusDays(60), null),
            Arguments.of("el día 60 solo", HOY.plusDays(60), HOY.plusDays(60), null),
            Arguments.of("termina el día 61", HOY.plusDays(55), HOY.plusDays(61), RangeViolation.BEYOND_HORIZON),
            Arguments.of("el día 61 solo", HOY.plusDays(61), HOY.plusDays(61), RangeViolation.BEYOND_HORIZON),
            Arguments.of("from posterior a to", HOY.plusDays(3), HOY.plusDays(2), RangeViolation.FROM_AFTER_TO),
            Arguments.of("al revés y en el pasado: primero el orden", HOY.minusDays(1), HOY.minusDays(2), RangeViolation.FROM_AFTER_TO),
            Arguments.of("largo y en el pasado: primero el largo", HOY.minusDays(1), HOY.plusDays(30), RangeViolation.TOO_LONG)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rangos")
    void elRangoSeValidaConLimitesInclusivos(String caso, LocalDate desde, LocalDate hasta, RangeViolation esperado) {
        assertThat(POLITICA.checkRange(desde, hasta, HOY)).isEqualTo(Optional.ofNullable(esperado));
    }

    @Test
    void unaFechaSePuedeReservarDesdeHoyHastaElUltimoDiaDelHorizonte() {
        assertThat(POLITICA.isBookableDate(HOY.minusDays(1), HOY)).isFalse();
        assertThat(POLITICA.isBookableDate(HOY, HOY)).isTrue();
        assertThat(POLITICA.isBookableDate(HOY.plusDays(60), HOY)).isTrue();
        assertThat(POLITICA.isBookableDate(HOY.plusDays(61), HOY)).isFalse();
    }

    @Test
    void elHorizonteEsConfigurable() {
        AvailabilityPolicy corta = new AvailabilityPolicy(ZoneId.of("UTC"), 0);

        assertThat(corta.isBookableDate(HOY, HOY)).isTrue();
        assertThat(corta.isBookableDate(HOY.plusDays(1), HOY)).isFalse();
    }

    @Test
    void hoyYAhoraSalenDeLaZonaDeAtencion() {
        // 02:30 UTC del 16/11 es todavía el 15/11 a las 23:30 en Mendoza (-03).
        Instant instante = Instant.parse("2026-11-16T02:30:00Z");

        assertThat(POLITICA.localDateTime(instante)).isEqualTo(LocalDateTime.parse("2026-11-15T23:30:00"));
        assertThat(new AvailabilityPolicy(ZoneId.of("UTC"), 60).localDateTime(instante)).isEqualTo(LocalDateTime.parse("2026-11-16T02:30:00"));
    }

    @Test
    void laZonaEsObligatoriaYElHorizonteNoPuedeSerNegativo() {
        assertThatThrownBy(() -> new AvailabilityPolicy(null, 60)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AvailabilityPolicy(ZoneId.of("UTC"), -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
