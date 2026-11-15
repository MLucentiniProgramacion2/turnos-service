package ar.edu.um.programacion2.turnos.reservation.infrastructure.messaging.producer;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.turnos.IntegrationTest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * La comprobación de fondo del topic de teléfono con el contexto entero y un broker que no existe ({@code localhost:1},
 * el de {@code src/test}): <strong>el arranque no la espera</strong>, y la falla queda como advertencia.
 * <p>
 * El plazo de fondo es de 3 s: el contexto queda listo (y este test empieza) antes de que el primer intento termine. Si
 * el listener del arranque corriera la comprobación en su propio hilo, el arranque tardaría esos 3 s y el intento ya
 * estaría terminado al empezar el test.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
    properties = {
        "catedra.kafka.producer.background-topic-check-enabled=true", "catedra.kafka.producer.background-topic-check-timeout=3s",
    }
)
class PhoneTopicCheckStartupIT {

    @Autowired
    private PhoneTopicCheck comprobacion;

    @Test
    void conElBrokerCaidoElContextoLevantaSinEsperarLaComprobacionYQuedaUnaAdvertencia(CapturedOutput salida) throws Exception {
        assertThat(comprobacion.firstBackgroundAttempt()).as("el primer intento sigue en curso con el contexto listo").isNotDone();

        comprobacion.firstBackgroundAttempt().get(10, TimeUnit.SECONDS);

        assertThat(comprobacion.isChecked()).isFalse();
        assertThat(salida.getAll())
            .contains("No se pudo comprobar el topic de teléfono en segundo plano (sin respuesta en 3000 ms); se reintenta cada 30 s")
            .doesNotContain("Topic de teléfono comprobado");
    }
}
