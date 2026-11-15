package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Un {@link TransactionTemplate} para los tests unitarios de los casos de uso: no abre nada, pero sabe si el código está
 * "adentro de una transacción". Con eso, {@link InMemoryReservationProcessRepository} exige una abierta (como
 * {@code MANDATORY}) y los tests comprueban que ningún pedido a la cátedra, al catálogo o a Kafka se hace adentro de una.
 */
class FakeTransactions extends TransactionTemplate {

    private boolean active;
    private int opened;

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        if (active) {
            throw new IllegalStateException("Una transacción adentro de otra: los casos de uso usan transacciones cortas y separadas");
        }
        active = true;
        opened++;
        try {
            return action.doInTransaction(new SimpleTransactionStatus());
        } finally {
            active = false;
        }
    }

    boolean isActive() {
        return active;
    }

    int opened() {
        return opened;
    }

    void requireActive() {
        if (!active) {
            throw new IllegalTransactionStateException("No hay una transacción abierta");
        }
    }
}
