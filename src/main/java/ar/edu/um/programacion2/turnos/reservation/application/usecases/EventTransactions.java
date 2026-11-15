package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las transacciones de los eventos de Kafka (CU-05 y CU-07), con su plazo: una transacción trabada no puede retener el
 * hilo del listener sin límite (ADR 0006).
 * <p>
 * Es un envoltorio y no un segundo bean {@code TransactionTemplate}: con uno propio, Spring Boot retira el suyo (su
 * autoconfiguración pide que no haya ninguno) y los casos de uso HTTP recibirían este, con plazo.
 */
public record EventTransactions(TransactionTemplate template) {
    public EventTransactions {
        Objects.requireNonNull(template, "Falta el TransactionTemplate");
    }
}
