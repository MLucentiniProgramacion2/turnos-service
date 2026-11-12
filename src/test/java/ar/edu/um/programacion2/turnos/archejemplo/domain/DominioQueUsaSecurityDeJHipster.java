package ar.edu.um.programacion2.turnos.archejemplo.domain;

import ar.edu.um.programacion2.turnos.security.SecurityUtils;

public class DominioQueUsaSecurityDeJHipster {

    public boolean puedeEditar() {
        return SecurityUtils.isAuthenticated();
    }
}
