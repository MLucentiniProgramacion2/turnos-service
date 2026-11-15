package ar.edu.um.programacion2.turnos.reservation.application.usecases;

import ar.edu.um.programacion2.turnos.reservation.domain.ports.out.ProcessedEventRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** La barrera de idempotencia en memoria, para los tests unitarios. Exige una transacción abierta, como el adaptador. */
class InMemoryProcessedEventRepository implements ProcessedEventRepository {

    private final FakeTransactions transactions;
    private final Map<String, String> registered = new LinkedHashMap<>();

    InMemoryProcessedEventRepository(FakeTransactions transactions) {
        this.transactions = transactions;
    }

    @Override
    public boolean registerIfAbsent(String eventId, String eventType, Instant processedAt) {
        transactions.requireActive();
        return registered.putIfAbsent(eventId, eventType) == null;
    }

    /** Los eventos registrados, {@code eventId -> eventType}, en orden. */
    Map<String, String> registered() {
        return Map.copyOf(registered);
    }
}
