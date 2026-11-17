package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraCancellation;
import ar.edu.um.programacion2.turnos.reservation.domain.model.CatedraReservationList;
import java.time.LocalDate;

/**
 * Las reservas de la cátedra (contrato §11 y §12): listarlas para CU-09 y CU-11, y cancelarlas para CU-10. Es un puerto
 * aparte de {@link HoldGateway}: son reservas, no holds, y así CU-09 no depende de un puerto con un {@code POST} que nunca
 * se repite.
 */
public interface AppointmentGateway {
    /**
     * {@code GET /api/appointments} con esos filtros, <strong>todas las páginas</strong>. La cátedra devuelve reservas de
     * toda la cuenta técnica: el filtro por {@code externalPatientId} es solo auxiliar, la propiedad sale de la tabla
     * local (DPT-01).
     * <p>
     * Es una consulta: nunca lanza {@link CatedraOutcomeUnknownException}. Un listado que no termina en el plazo, o cuyo
     * total cambia entre páginas, es {@link CatedraUnavailableException} (repetirlo es seguro); uno con más páginas que
     * el tope, o con cabeceras de paginación que no respetan el contrato, es {@link CatedraInvalidResponseException}.
     *
     * @param from fecha mínima del turno, incluida
     * @param to fecha máxima del turno, incluida
     */
    CatedraReservationList listReservations(String externalPatientId, LocalDate from, LocalDate to);

    /**
     * {@code POST /api/appointments/{reservationProcessId}/cancel} (contrato §12), con el motivo si hay uno y sin cuerpo si
     * no. Es una operación <strong>con efectos</strong>: un solo intento, y una falla después de conectar es
     * {@link CatedraOutcomeUnknownException} (contrato §18.4), igual que el hold. Cancelar algo ya cancelado devuelve su
     * estado: repetir es seguro.
     *
     * @param reason el motivo del usuario, hasta 500 caracteres, o {@code null}
     */
    CatedraCancellation cancel(String reservationProcessId, String reason);
}
