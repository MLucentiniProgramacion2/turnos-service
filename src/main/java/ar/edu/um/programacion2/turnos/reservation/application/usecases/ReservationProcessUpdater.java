package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcess;
import ar.edu.um.programacion2.turnos.reservation.domain.model.ReservationProcessId;
import ar.edu.um.programacion2.turnos.reservation.domain.model.TransitionResult;
import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ReservationProcessRepository;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aplica un hecho a un proceso desde un pedido HTTP (CU-04 y CU-06): <strong>releer, aplicar la transición y guardar</strong>,
 * todo en una transacción corta.
 * <p>
 * Se relee siempre, en vez de guardar el objeto que el caso de uso tiene en memoria: entre un pedido a la cátedra o a Kafka
 * y el guardado pasan segundos, y en ese tiempo el listener pudo haber cambiado la fila (CU-04 13a y 13b, CU-06 7a). Con
 * la fila recién leída, la máquina de estados decide: si el hecho ya no corresponde, da {@code NoEffect} y nadie retrocede.
 * <p>
 * Aun así queda una ventana entre la lectura y la escritura. Ahí actúa el bloqueo optimista: el {@code UPDATE} no encuentra
 * la versión leída y lanza {@link OptimisticLockingFailureException}. Se reintenta hasta {@value #MAX_ATTEMPTS} veces, cada
 * una en una transacción nueva que ve el estado nuevo. Si se agotan, la excepción sube: no se insiste sin límite.
 */
@Component
@RequiredArgsConstructor
public class ReservationProcessUpdater {

    static final int MAX_ATTEMPTS = 3;

    private static final Logger LOG = LoggerFactory.getLogger(ReservationProcessUpdater.class);

    private final ReservationProcessRepository repository;
    private final TransactionTemplate transactionTemplate;

    /** El proceso como quedó guardado y lo que resultó de aplicar el hecho. */
    public record Updated(ReservationProcess process, TransitionResult result) {
        public boolean applied() {
            return result instanceof TransitionResult.Applied;
        }
    }

    /** Guarda solo si la transición cambió el estado. */
    public Updated apply(ReservationProcessId id, long userId, Function<ReservationProcess, TransitionResult> transition) {
        return apply(id, userId, transition, false);
    }

    /**
     * @param saveAlways guarda aunque el estado no cambie: {@code phoneSubmitted} registra el {@code eventId} enviado también
     *     con {@code NoEffect} (CU-06 7a)
     * @throws OptimisticLockingFailureException si el proceso cambió en cada uno de los intentos
     * @throws IllegalStateException si el proceso no existe o no es de ese usuario (el caso de uso ya lo había leído)
     */
    public Updated apply(ReservationProcessId id, long userId, Function<ReservationProcess, TransitionResult> transition, boolean saveAlways) {
        Objects.requireNonNull(transition, "Falta la transición");
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    ReservationProcess process = repository
                        .findByIdAndOwner(id, userId)
                        .orElseThrow(() -> new IllegalStateException("El proceso " + id + " ya no existe"));
                    TransitionResult result = transition.apply(process);
                    boolean changed = saveAlways || result instanceof TransitionResult.Applied;
                    return new Updated(changed ? repository.save(process) : process, result);
                });
            } catch (OptimisticLockingFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                LOG.info("El proceso {} cambió mientras se guardaba (intento {} de {}): se relee", id, attempt, MAX_ATTEMPTS);
            }
        }
    }
}
