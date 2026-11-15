package ar.edu.um.programacion2.turnos.reservation.domain.ports.out;

import java.time.Instant;

/**
 * Los eventos de Kafka de la cátedra ya procesados: la barrera de idempotencia (DPT-15, contrato §18.1). La transacción
 * la abre el caso de uso: el registro se confirma junto con el cambio de estado, o no se confirma.
 */
public interface ProcessedEventRepository {
    /**
     * Registra el evento si no estaba, en una sola sentencia. Es la barrera: <strong>se inserta y se mira el
     * resultado</strong>, en vez de consultar y después insertar. Si otra transacción está registrando el mismo
     * {@code eventId} al mismo tiempo, esta espera a que termine: si la otra confirma, devuelve {@code false}; si se
     * deshace, inserta y devuelve {@code true}. Nunca lanza por un duplicado, así que no deshace la transacción de quien
     * llama.
     *
     * @return {@code true} si lo registró ahora; {@code false} si ya estaba (entrega duplicada)
     */
    boolean registerIfAbsent(String eventId, String eventType, Instant processedAt);
}
