package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import org.junit.jupiter.api.Test;

class CatedraErrorCodeTest {

    @Test
    void reconoceCadaCodeDeLaMatrizYLoDemasEsNoReconocido() {
        assertThat(CatedraErrorCode.from("HOLD_EXPIRED")).isEqualTo(CatedraErrorCode.HOLD_EXPIRED);
        assertThat(CatedraErrorCode.from("RESERVATION_PROCESS_ALREADY_CONFIRMED")).isEqualTo(
            CatedraErrorCode.RESERVATION_PROCESS_ALREADY_CONFIRMED
        );
        assertThat(CatedraErrorCode.from(null)).isEqualTo(CatedraErrorCode.UNRECOGNIZED);
        assertThat(CatedraErrorCode.from("hold_expired")).isEqualTo(CatedraErrorCode.UNRECOGNIZED);
        assertThat(CatedraErrorCode.from("UNRECOGNIZED")).isEqualTo(CatedraErrorCode.UNRECOGNIZED);
        assertThat(CatedraErrorCode.from("USERNAME_ALREADY_EXISTS")).as("de registro: turnos no lo usa").isEqualTo(
            CatedraErrorCode.UNRECOGNIZED
        );
    }

    @Test
    void tieneLos16CodesDeLasOperacionesDeTurnosMasUnrecognized() {
        assertThat(CatedraErrorCode.values()).hasSize(17);
    }
}
