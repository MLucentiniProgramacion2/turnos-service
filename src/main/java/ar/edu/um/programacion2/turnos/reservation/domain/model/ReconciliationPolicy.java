package ar.edu.um.programacion2.turnos.reservation.domain.model;

import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.CREATING_HOLD;
import static ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessStatus.HOLD_CREATED;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction.Apply;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction.RetryConfirm;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction.Wait;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReconciliationAction.WaitReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * La tabla de CU-11, como función pura: qué hacer con un proceso intermedio, según su estado, su antigüedad, lo que informa
 * la cátedra y {@code expiresAt}. El caso de uso solo busca los datos, pide la decisión y la aplica; y la vuelve a pedir
 * sobre el proceso releído (5a), así un evento que lo cerró mientras tanto deja la decisión sin efecto.
 * <p>
 * Dos pasos, para no preguntarle a la cátedra si no hace falta:
 * <ol>
 * <li>{@link #needsCatedra}: sin la cátedra. {@code CREATING_HOLD} viejo se cierra ahí mismo (no hay {@code holdId} con
 * qué preguntar, DPT-06); el resto pregunta solo si ya corresponde.</li>
 * <li>{@link #decide}: con lo que dijo la cátedra.</li>
 * </ol>
 * <table>
 * <tr><th>Estado local</th><th>Cuándo</th><th>Cátedra</th><th>Decisión</th></tr>
 * <tr><td>{@code CREATING_HOLD}</td><td>más de {@code minAge} sin cambios (DPT-24)</td><td>—</td><td>{@code FAILED} con
 * {@code HOLD_OUTCOME_UNKNOWN}</td></tr>
 * <tr><td>{@code HOLD_CREATED} (3a)</td><td>más de {@code minAge} sin cambios</td><td>{@code PHONE_PENDING}</td>
 * <td>{@code confirmAccepted}</td></tr>
 * <tr><td></td><td></td><td>{@code CONFIRMED}, {@code CANCELLED}</td><td>la convergencia de CU-09</td></tr>
 * <tr><td></td><td></td><td>{@code FAILED}</td><td>la regla de 3b</td></tr>
 * <tr><td></td><td></td><td>ausente (lista completa), antes de {@code expiresAt}</td><td>reintentar el confirm</td></tr>
 * <tr><td></td><td></td><td>ausente, después de {@code expiresAt} + margen</td><td>{@code EXPIRED}</td></tr>
 * <tr><td>esperando el teléfono (3b)</td><td>después de {@code expiresAt} + margen (DPT-09)</td><td>{@code CONFIRMED},
 * {@code CANCELLED}</td><td>la convergencia</td></tr>
 * <tr><td></td><td></td><td>{@code FAILED} o ausente</td><td>{@code EXPIRED}</td></tr>
 * <tr><td></td><td></td><td>{@code PHONE_PENDING}</td><td>esperar hasta {@code expiresAt} + espera máxima; después
 * {@code EXPIRED}</td></tr>
 * </table>
 * Los bordes son exclusivos, como dice el CU: "más de 1 minuto", "después de {@code expiresAt} + 1 minuto". El confirm se
 * reintenta solo <strong>antes</strong> de {@code expiresAt} (DPT-08), con el mismo criterio que {@code canSubmitPhone}.
 *
 * @param minAge antigüedad mínima de {@code CREATING_HOLD} y {@code HOLD_CREATED} (DPT-24), medida con
 *     {@code statusChangedAt}
 * @param expiryMargin margen sobre {@code expiresAt} para los que esperan el teléfono o el resultado (DPT-09)
 * @param phonePendingMaxWait cuánto se espera, desde {@code expiresAt}, a una reserva que la cátedra sigue teniendo en
 *     {@code PHONE_PENDING} (DPT-09)
 */
public record ReconciliationPolicy(Duration minAge, Duration expiryMargin, Duration phonePendingMaxWait) {
    /** Motivos propios del cierre (salen en {@code closing.reason} del contrato REST). */
    public static final String HOLD_OUTCOME_UNKNOWN = "HOLD_OUTCOME_UNKNOWN";

    public static final String NOT_FOUND_AFTER_EXPIRY = "NOT_FOUND_AFTER_EXPIRY";
    public static final String FAILED_IN_CATEDRA = "FAILED_IN_CATEDRA";
    public static final String PHONE_PENDING_TIMEOUT = "PHONE_PENDING_TIMEOUT";

    public ReconciliationPolicy {
        requirePositive(minAge, "la antigüedad mínima");
        Objects.requireNonNull(expiryMargin, "Falta el margen sobre expiresAt");
        if (expiryMargin.isNegative()) {
            throw new IllegalArgumentException("El margen sobre expiresAt no puede ser negativo");
        }
        requirePositive(phonePendingMaxWait, "la espera máxima de PHONE_PENDING");
        if (phonePendingMaxWait.compareTo(expiryMargin) <= 0) {
            throw new IllegalArgumentException("La espera máxima de PHONE_PENDING tiene que ser mayor que el margen sobre expiresAt");
        }
    }

    /** El primer paso, sin la cátedra: {@code Optional.empty()} si hay que preguntarle; si no, la decisión. */
    public Optional<ReconciliationAction> needsCatedra(ReservationProcess process, Instant now) {
        Objects.requireNonNull(now, "Falta el instante actual");
        ReservationProcessStatus status = process.getStatus();
        if (!status.isIntermediate()) {
            return Optional.of(new Wait(WaitReason.NOT_INTERMEDIATE));
        }
        if (status == CREATING_HOLD || status == HOLD_CREATED) {
            if (!olderThanMinAge(process, now)) {
                return Optional.of(new Wait(WaitReason.NOT_DUE));
            }
            if (status == CREATING_HOLD) {
                // No hay holdId: no se puede preguntar ni retomar (DPT-06). Si el hold se creó, vence solo.
                return Optional.of(new Apply((p, at) -> p.failed(new Closing(HOLD_OUTCOME_UNKNOWN, null), at), HOLD_OUTCOME_UNKNOWN));
            }
            return Optional.empty();
        }
        return pastMargin(process, now) ? Optional.empty() : Optional.of(new Wait(WaitReason.NOT_DUE));
    }

    /**
     * El segundo paso, con lo que informa la cátedra. Recibe el proceso tal como está ahora (en 5a, el releído): si ya no
     * corresponde hacer nada, da {@link Wait}.
     *
     * @param inCatedra la reserva de ese proceso en la lista de la cátedra, si está
     * @param listProvesAbsence si la lista se leyó entera (sin ítems ignorados): solo entonces "no está" prueba algo
     */
    public ReconciliationAction decide(ReservationProcess process, Optional<CatedraReservation> inCatedra, boolean listProvesAbsence, Instant now) {
        Optional<ReconciliationAction> first = needsCatedra(process, now);
        if (first.isPresent()) {
            return first.orElseThrow();
        }
        boolean holdCreated = process.getStatus() == HOLD_CREATED;
        if (inCatedra.isEmpty()) {
            if (!listProvesAbsence) {
                return new Wait(WaitReason.LIST_INCOMPLETE);
            }
            if (holdCreated && now.isBefore(expiresAt(process))) {
                return new RetryConfirm();
            }
            if (!pastMargin(process, now)) {
                return new Wait(WaitReason.EXPIRING);
            }
            return expire(NOT_FOUND_AFTER_EXPIRY);
        }
        CatedraReservation reservation = inCatedra.orElseThrow();
        return switch (reservation.status()) {
            case CONFIRMED, CANCELLED -> converge(reservation, now);
            case PHONE_PENDING -> {
                if (holdCreated) {
                    // 3a: el confirm se aplicó. La lista no informa expiresAt: se conserva el del hold.
                    yield new Apply((p, at) -> p.confirmAccepted(null, at), "PHONE_PENDING_IN_CATEDRA");
                }
                yield now.isAfter(expiresAt(process).plus(phonePendingMaxWait)) ? expire(PHONE_PENDING_TIMEOUT) : new Wait(WaitReason.PHONE_PENDING);
            }
            case FAILED -> pastMargin(process, now) ? expire(FAILED_IN_CATEDRA) : new Wait(WaitReason.EXPIRING);
            case UNRECOGNIZED -> new Wait(WaitReason.UNUSABLE_CATEDRA_DATA);
        };
    }

    private static ReconciliationAction converge(CatedraReservation reservation, Instant now) {
        ConvergenceFact fact = ReservationConvergence.factFor(reservation, now);
        if (fact instanceof ConvergenceFact.Nothing) {
            return new Wait(WaitReason.UNUSABLE_CATEDRA_DATA);
        }
        return new Apply(fact::applyTo, "CATEDRA_" + reservation.status());
    }

    private static Apply expire(String reason) {
        return new Apply((p, at) -> p.expired(new Closing(reason, null), at), reason);
    }

    private boolean olderThanMinAge(ReservationProcess process, Instant now) {
        return process.getStatusChangedAt().plus(minAge).isBefore(now);
    }

    private boolean pastMargin(ReservationProcess process, Instant now) {
        return now.isAfter(expiresAt(process).plus(expiryMargin));
    }

    /** Todo intermedio salvo {@code CREATING_HOLD} tiene hold (lo garantizan el modelo y el {@code CHECK}). */
    private static Instant expiresAt(ReservationProcess process) {
        return process.getHold().orElseThrow().expiresAt();
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, "Falta " + name);
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " tiene que ser positiva");
        }
    }
}
