package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Las reglas de los valores del proceso: cada record valida en su constructor. */
class ReservationValuesTest {

    @Test
    void losIntermediosSonExactamenteLosDeDpt05() {
        assertThat(ReservationProcessStatus.intermediates()).containsExactly(
            ReservationProcessStatus.CREATING_HOLD,
            ReservationProcessStatus.HOLD_CREATED,
            ReservationProcessStatus.WAITING_FOR_PHONE_REQUEST,
            ReservationProcessStatus.PHONE_REQUESTED,
            ReservationProcessStatus.PHONE_SUBMITTED,
            ReservationProcessStatus.PHONE_REJECTED
        );
        assertThat(ReservationProcessStatus.CONFIRMED.isIntermediate()).isFalse();
        assertThat(ReservationProcessStatus.CONFIRMED.isFinal()).isFalse();
        assertThat(ReservationProcessStatus.values()).filteredOn(ReservationProcessStatus::isFinal).containsExactly(
            ReservationProcessStatus.CANCELLED,
            ReservationProcessStatus.EXPIRED,
            ReservationProcessStatus.INVALID,
            ReservationProcessStatus.FAILED
        );
    }

    @Test
    void elTelefonoSeNormalizaComoEnLaCatedra() {
        assertThat(PhoneNumber.of("+54 9 (261) 555-12.34").value()).isEqualTo("+5492615551234");
        assertThat(PhoneNumber.of("2615551234").value()).isEqualTo("2615551234");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "123456", "1234567890123456", "+54-261-abc-1234", "++5492615551234", "54+92615551234" })
    void unTelefonoFueraDelFormatoEsInvalidoYElMensajeNoLoRepite(String telefono) {
        assertThatThrownBy(() -> PhoneNumber.of(telefono))
            .isInstanceOf(IllegalArgumentException.class)
            .satisfies(e -> {
                if (!telefono.isEmpty()) {
                    assertThat(e.getMessage()).doesNotContain(telefono);
                }
            });
    }

    @Test
    void elTelefonoYElNombreDelPacienteNoSalenEnteroEnElToString() {
        assertThat(PhoneNumber.of("+5492615551234").toString()).isEqualTo("PhoneNumber[***34]");
        assertThat(new PatientName("Juan", "Perez").toString()).isEqualTo("PatientName[firstName=J***, lastName=P***]");
    }

    @Test
    void elNombreDelPacienteVaDe2A100Caracteres() {
        assertThat(new PatientName("Jo", "x".repeat(100)).lastName()).hasSize(100);
        assertThatThrownBy(() -> new PatientName("J", "Perez")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PatientName("Juan", "x".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PatientName("  ", "Perez")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PatientName(null, "Perez")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void elTurnoTieneQueTerminarDespuesDeEmpezar() {
        LocalDate fecha = LocalDate.parse("2026-12-14");
        assertThat(new AppointmentSlot(202, fecha, LocalTime.parse("23:30"), LocalTime.parse("23:59")).endTime()).isEqualTo("23:59");
        assertThatThrownBy(() -> new AppointmentSlot(202, fecha, LocalTime.parse("10:00"), LocalTime.parse("10:00"))).isInstanceOf(
            IllegalArgumentException.class
        );
        // Un turno que terminaría a las 00:00 o cruzaría la medianoche no existe: su horario sería vacío (#27 del catálogo).
        assertThatThrownBy(() -> new AppointmentSlot(202, fecha, LocalTime.parse("23:30"), LocalTime.MIDNIGHT)).isInstanceOf(
            IllegalArgumentException.class
        );
        assertThatThrownBy(() -> new AppointmentSlot(0, fecha, LocalTime.parse("09:00"), LocalTime.parse("09:30"))).isInstanceOf(
            IllegalArgumentException.class
        );
    }

    @Test
    void elDuenoTieneIdPositivoYLoginDeHasta50() {
        assertThat(new ProcessOwner(42, "ana").externalPatientId()).isEqualTo("user-42");
        assertThatThrownBy(() -> new ProcessOwner(0, "ana")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcessOwner(42, "x".repeat(51))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elMotivoDeCancelacionEsOpcionalYDeHasta500() {
        Instant ahora = Instant.parse("2026-12-10T12:00:00Z");
        assertThat(new Cancellation(null, ahora).reason()).isNull();
        assertThat(new Cancellation("x".repeat(500), ahora).reason()).hasSize(500);
        assertThatThrownBy(() -> new Cancellation("x".repeat(501), ahora)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Cancellation(" ", ahora)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void losDatosDeLaCatedraNoPuedenEstarEnBlanco() {
        Instant vence = Instant.parse("2026-12-10T12:10:00Z");
        assertThatThrownBy(() -> new CatedraHold(" ", "process-1", vence)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatedraHold("hold-1", "process-1", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PhoneRequest("", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Confirmation(0, vence)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProfessionalSnapshot("Ana", "Gomez", "Clínica", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
