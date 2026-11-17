package ar.edu.um.programacion2.turnos.reservation.infrastructure.client.catedra;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.CatedraException;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Comprobación manual: {@code GET /api/appointments} contra la cátedra real, con el cliente de turnos (CU-09 y CU-11).
 * <strong>Nunca corre en el CI</strong> (el nombre no es {@code *Test} ni {@code *IT}, y exige
 * {@code -Dcatedra.manual=true}). No tiene efectos: es un {@code GET}.
 * <p>
 * Con {@code -Dsize=1} fuerza la paginación de verdad: con dos reservas son dos páginas, recorridas con {@code page + 1}
 * hasta {@code X-Total-Count}, sin seguir el {@code Link} (que trae el host de la cátedra).
 *
 * <pre>./mvnw test -Dtest=CatedraAppointmentsManualCheck -Dcatedra.manual=true [-Dpaciente=user-1101 -Ddesde=2027-01-05 -Dhasta=2027-01-05 -Dsize=1]</pre>
 *
 * Imprime una línea por reserva: IDs, turno, estado e instantes. <strong>Nunca</strong> el teléfono ni los nombres del
 * paciente (el cliente ni siquiera los lee), el token ni la URL.
 */
@EnabledIfSystemProperty(named = "catedra.manual", matches = "true")
class CatedraAppointmentsManualCheck {

    @Test
    void listaLasReservasDeLaCatedraRealConTodasSusPaginas() throws IOException {
        String paciente = System.getProperty("paciente", "user-1101");
        LocalDate desde = LocalDate.parse(System.getProperty("desde", "2027-01-05"));
        LocalDate hasta = LocalDate.parse(System.getProperty("hasta", "2027-01-05"));
        CatedraApiProperties properties = CatedraManualCheckSupport.properties();
        properties.setListPageSize(Integer.parseInt(System.getProperty("size", "1")));
        CatedraAppointmentClient client = new CatedraAppointmentClient(CatedraManualCheckSupport.restClient(properties), properties);

        long antes = System.nanoTime();
        CatedraReservationList lista;
        try {
            lista = client.listReservations(paciente, desde, hasta);
        } catch (CatedraException e) {
            throw CatedraManualCheckSupport.oneLineFailure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        long ms = Duration.ofNanos(System.nanoTime() - antes).toMillis();
        System.out.println(
            "paciente=" + paciente + " desde=" + desde + " hasta=" + hasta + " size=" + properties.getListPageSize() + " reservas=" +
            lista.items().size() + " ignoradas=" + lista.ignoredItems() + " en " + ms + " ms"
        );
        lista
            .items()
            .forEach(r ->
                System.out.println(
                    "  " + r.reservationProcessId() + " reservationId=" + r.reservationId() + " " + r.date() + " " + r.startTime() + " " + r.status() +
                    " confirmedAt=" + r.confirmedAt() + " cancelledAt=" + r.cancelledAt()
                )
            );
    }
}
