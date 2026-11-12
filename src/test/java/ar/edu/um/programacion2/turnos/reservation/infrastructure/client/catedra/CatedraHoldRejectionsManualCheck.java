package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.reservation.domain.model.AppointmentSlot;
import ar.edu.um.programacion2.turnos.reservation.domain.model.PatientName;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraErrorCode;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraRejectedException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Comprobación manual: los tres rechazos de la cátedra real que <strong>no crean nada</strong> (sección 7 del issue #10,
 * medidos el 11/11), para probar la clasificación por status y {@code code} contra la cátedra de verdad:
 * <ol>
 * <li>hold de un profesional inexistente (999999): {@code 404 PROFESSIONAL_NOT_FOUND};</li>
 * <li>hold del profesional 202 a las 03:17, fuera de su agenda: {@code 400 INVALID_SLOT};</li>
 * <li>confirm de un hold inexistente: {@code 404 HOLD_NOT_FOUND}.</li>
 * </ol>
 * Si la cátedra llegara a aceptar el segundo (si la agenda del 202 cambiara), crearía un hold: falla con un aviso. Ese hold
 * vence solo y nunca se convierte en reserva, porque no se confirma (DPT-06).
 * <p>
 * No corre en el CI (ver {@link CatedraOccupancyManualCheck}). Con ZeroTier conectado:
 *
 * <pre>./mvnw test -Dtest=CatedraHoldRejectionsManualCheck -Dcatedra.manual=true</pre>
 */
@EnabledIfSystemProperty(named = "catedra.manual", matches = "true")
class CatedraHoldRejectionsManualCheck {

    private static final LocalDate DIA = LocalDate.parse("2026-12-14");

    @Test
    void losTresRechazosSinEfectosSeClasificanPorStatusYCode() throws IOException {
        CatedraHoldClient client = new CatedraHoldClient(CatedraManualCheckSupport.restClient());

        rechazo(
            "hold de un profesional inexistente",
            404,
            CatedraErrorCode.PROFESSIONAL_NOT_FOUND,
            () -> client.createHold(new AppointmentSlot(999_999, DIA, LocalTime.of(9, 0), LocalTime.of(9, 30)), "user-0")
        );
        rechazo(
            "hold fuera de la agenda del 202",
            400,
            CatedraErrorCode.INVALID_SLOT,
            () -> client.createHold(new AppointmentSlot(202, DIA, LocalTime.of(3, 17), LocalTime.of(3, 47)), "user-0")
        );
        rechazo(
            "confirm de un hold inexistente",
            404,
            CatedraErrorCode.HOLD_NOT_FOUND,
            () -> client.confirmHold("hold-no-existe", "user-0", new PatientName("Prueba", "Prueba"))
        );
    }

    private static void rechazo(String caso, int status, CatedraErrorCode code, Runnable pedido) {
        try {
            pedido.run();
        } catch (CatedraRejectedException e) {
            System.out.println(caso + ": " + e.status() + " " + e.code());
            assertThat(e.status()).as(caso).isEqualTo(status);
            assertThat(e.code()).as(caso).isEqualTo(code);
            return;
        } catch (CatedraException e) {
            throw CatedraManualCheckSupport.oneLineFailure(caso + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        throw CatedraManualCheckSupport.oneLineFailure(
            caso + ": ¡la cátedra lo aceptó! Si se creó un hold, vence solo y nunca se convierte en reserva (DPT-06)"
        );
    }
}
