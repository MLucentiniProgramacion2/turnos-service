package ar.edu.um.programacion2.turnos.archejemplo.infrastructure.messaging.listener;

import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.persistence.entity.ProfesionalEjemploEntity;

/** Viola la regla: un listener que usa una entidad JPA en vez de llamar al service. */
public class ListenerQueUsaPersistence {

    private ProfesionalEjemploEntity entidad;
}
