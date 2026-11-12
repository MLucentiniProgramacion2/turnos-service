package ar.edu.um.programacion2.turnos.archejemplo.infrastructure.periodic;

import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.client.ClienteRestEjemplo;

/** Viola la regla: un disparador periódico (CU-11) que llama a la cátedra por su cuenta en vez de llamar al service. */
public class PeriodicQueUsaClient {

    public String reconciliar(ClienteRestEjemplo cliente) {
        return cliente.pedir("/api/appointments");
    }
}
