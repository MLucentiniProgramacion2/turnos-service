package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.listener;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * Lo último que pasa con un evento cuando se agotan los reintentos (DPT-22): ERROR en el log y el
 * {@code DefaultErrorHandler} confirma el offset. El proceso queda como estaba y lo recupera la reconciliación (CU-11).
 * <p>
 * <strong>Nunca lanza una excepción.</strong> Si lo hiciera, el manejador tomaría el evento como no recuperado y lo
 * volvería a entregar sin fin (medido en el catálogo: 18 entregas en 9 s y el offset sin moverse). En el log van la
 * partición, el offset y la clave (el {@code reservationProcessId}, que no es un dato personal), nunca el cuerpo.
 */
class ReservationEventRecoverer implements ConsumerRecordRecoverer {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationEventRecoverer.class);

    @Override
    public void accept(ConsumerRecord<?, ?> message, Exception failure) {
        LOG.error(
            "Evento de reserva descartado después de los reintentos (partición {}, offset {}, clave {}): {}; se confirma el offset y lo recupera la reconciliación",
            message.partition(),
            message.offset(),
            message.key(),
            CatedraKafkaConsumerConfiguration.describe(failure)
        );
    }
}
