package ar.edu.um.programacion2.turnos.archejemplo.infrastructure;

import ar.edu.um.programacion2.turnos.web.rest.errors.BadRequestAlertException;

public class FeatureQueUsaWebDeJHipster {

    public void rechazar() {
        throw new BadRequestAlertException("Profesional inválido", "profesional", "invalido");
    }
}
