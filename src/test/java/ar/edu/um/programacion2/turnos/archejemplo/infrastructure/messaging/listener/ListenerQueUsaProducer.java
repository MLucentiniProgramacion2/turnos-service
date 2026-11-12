package ar.edu.um.programacion2.turnos.archejemplo.infrastructure.messaging.listener;

import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.messaging.producer.ProductorEjemplo;

/** Viola la regla: el listener (entrada) publica por su cuenta con el productor (salida), aunque estén los dos en messaging. */
public class ListenerQueUsaProducer {

    public String responder(ProductorEjemplo productor) {
        return productor.publicar("process-1");
    }
}
