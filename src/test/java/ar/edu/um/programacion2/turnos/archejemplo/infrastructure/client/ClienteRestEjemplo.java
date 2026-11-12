package ar.edu.um.programacion2.turnos.archejemplo.infrastructure.client;

/** Adaptador de salida REST de ejemplo. No viola nada: es el destino prohibido de {@code WebQueUsaClient}. */
public class ClienteRestEjemplo {

    public String pedir(String ruta) {
        return ruta;
    }
}
