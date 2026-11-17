package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las transacciones de las consultas (CU-08, CU-09 y CU-12): solo lectura y en {@code REPEATABLE_READ}, para que una
 * página y su total salgan de la misma foto de la base (como las consultas del catálogo).
 * <p>
 * Es un envoltorio y no un segundo bean {@code TransactionTemplate}, por lo mismo que {@link EventTransactions}: con uno
 * propio, Spring Boot retira el suyo y los demás casos de uso recibirían este, de solo lectura.
 */
public record ReadTransactions(TransactionTemplate template) {
    public ReadTransactions {
        Objects.requireNonNull(template, "Falta el TransactionTemplate");
    }
}
