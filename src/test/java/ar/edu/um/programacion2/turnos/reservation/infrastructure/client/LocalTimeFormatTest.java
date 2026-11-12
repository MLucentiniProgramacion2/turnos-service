package ar.edu.um.programacion2.turnos.reservation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** El formateador de horas que comparten los clientes de la cátedra y del catálogo (contrato §4). */
class LocalTimeFormatTest {

    @ParameterizedTest
    @CsvSource({ "09:00, 09:00", "09:00:00, 09:00", "13:00:30, 13:00:30", "00:00, 00:00", "23:59:59, 23:59:59" })
    void aceptaHHmmYHHmmss(String texto, LocalTime esperada) {
        assertThat(LocalTimeFormat.parse(texto, "startTime")).isEqualTo(esperada);
    }

    @ParameterizedTest
    @ValueSource(strings = { "9:00", "24:00", "09:60", "09:00:60", "09:00:00.5", "09:00Z", " 09:00", "09", "09:00:00:00", "nueve" })
    void rechazaLoQueNoEsHHmmNiHHmmssSinRepetirElValor(String texto) {
        assertThatThrownBy(() -> LocalTimeFormat.parse(texto, "startTime"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("startTime no es una hora HH:mm o HH:mm:ss");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "  " })
    void sinValorDiceQueFaltaElCampo(String texto) {
        assertThatThrownBy(() -> LocalTimeFormat.parse(texto, "endTime")).isInstanceOf(IllegalArgumentException.class).hasMessage(
            "Falta endTime"
        );
    }

    @Test
    void formateaConSegundosSoloSiLosHay() {
        assertThat(LocalTimeFormat.format(LocalTime.of(9, 30))).isEqualTo("09:30");
        assertThat(LocalTimeFormat.format(LocalTime.of(13, 0, 30))).isEqualTo("13:00:30");
        assertThat(LocalTimeFormat.format(LocalTime.of(0, 0))).isEqualTo("00:00");
        assertThat(LocalTimeFormat.format(LocalTime.of(9, 30, 0, 500_000_000))).as("sin fracciones").isEqualTo("09:30");
    }

    @Test
    void loQueFormateaSeVuelveALeerIgual() {
        for (LocalTime hora : new LocalTime[] { LocalTime.of(9, 30), LocalTime.of(13, 0, 30), LocalTime.MIDNIGHT, LocalTime.of(23, 59, 59) }) {
            assertThat(LocalTimeFormat.parse(LocalTimeFormat.format(hora), "hora")).isEqualTo(hora);
        }
    }
}
