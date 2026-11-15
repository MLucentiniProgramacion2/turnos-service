package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado de iniciar una reserva (CU-04). Es {@code sealed}: el controller distingue el {@code 202} del camino normal del
 * {@code 200} de un pedido repetido por el tipo del resultado, no por una bandera, y cada falla esperable tiene su caso.
 */
public sealed interface StartReservationResult {
    /**
     * El proceso quedó iniciado ({@code 202}). Su estado es el actual: normalmente {@code WAITING_FOR_PHONE_REQUEST}, pero
     * puede ser {@code HOLD_CREATED} (11a: el confirm quedó para reconciliar) o uno más avanzado si un evento de Kafka
     * llegó antes (13a, 13b).
     */
    record Started(ReservationProcess process) implements StartReservationResult {
        public Started {
            Objects.requireNonNull(process, "Falta el proceso");
        }
    }

    /** 3a: el usuario ya tenía un proceso en curso o confirmado para ese turno ({@code 200}). No se creó nada. */
    record AlreadyExists(ReservationProcess process) implements StartReservationResult {
        public AlreadyExists {
            Objects.requireNonNull(process, "Falta el proceso");
        }
    }

    /**
     * 4a: el usuario tiene un proceso en curso para otro turno.
     *
     * @param existing el proceso en curso (es del mismo usuario), o {@code null} si terminó justo antes de poder leerlo
     */
    record AnotherInProgress(ReservationProcessId existing) implements StartReservationResult {
        public Optional<ReservationProcessId> existingId() {
            return Optional.ofNullable(existing);
        }
    }

    /** 2b: la fecha ya pasó o está más allá del horizonte (DPT-10). */
    record InvalidDate() implements StartReservationResult {}

    /** 2b, 6c y 8c: el turno ya empezó, la hora tiene segundos o no es un turno de un horario habilitado. */
    record InvalidSlot() implements StartReservationResult {}

    /** 6a, 8d y 11c. */
    record ProfessionalNotFound() implements StartReservationResult {}

    /** 6b y 8c. */
    record ProfessionalDisabled() implements StartReservationResult {}

    /** El usuario del token ya no existe. */
    record UserNotFound() implements StartReservationResult {}

    /** El nombre del perfil no sirve como nombre de paciente (contrato §10). */
    record ProfileIncomplete() implements StartReservationResult {}

    /** 8b y 11c: otro tomó el turno. */
    record SlotNotAvailable() implements StartReservationResult {}

    /** 11b: el hold venció antes del confirm. */
    record Expired() implements StartReservationResult {}

    /** 8a: no se sabe si el hold se creó. El turno puede quedar bloqueado unos minutos (DPT-06). */
    record HoldOutcomeUnknown() implements StartReservationResult {}

    /** El hold se creó, pero no se pudo guardar: tampoco se confirmó. El turno queda bloqueado hasta que venza. */
    record HoldNotRecorded() implements StartReservationResult {}

    /** 5a (DPT-11), y también si el catálogo rechaza el token de servicio. */
    record CatalogUnavailable(Duration retryAfter) implements StartReservationResult {}

    /** No se pudo conectar con la cátedra para crear el hold (no se creó), o rechazó la credencial técnica. */
    record CatedraUnavailable() implements StartReservationResult {}

    record UpstreamInvalidResponse() implements StartReservationResult {}

    /** 8e y 11e: un rechazo que solo se explica por un error nuestro. */
    record InternalError() implements StartReservationResult {}
}
