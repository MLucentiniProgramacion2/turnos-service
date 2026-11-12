package ar.edu.um.programacion2.turnos.archejemplo.infrastructure.messaging.producer;

/** Adaptador de salida a Kafka, como el productor de {@code AdditionalInformationSubmitted}. Cumple todas las reglas. */
public class ProductorEjemplo {

    public String publicar(String clave) {
        return clave;
    }
}
