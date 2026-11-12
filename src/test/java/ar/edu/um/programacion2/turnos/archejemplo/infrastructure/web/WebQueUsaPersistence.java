package ar.edu.um.programacion2.turnos.archejemplo.infrastructure.web;

import ar.edu.um.programacion2.turnos.archejemplo.infrastructure.persistence.entity.ProfesionalEjemploEntity;

public class WebQueUsaPersistence {

    public Long responder(ProfesionalEjemploEntity entidad) {
        return entidad.getId();
    }
}
