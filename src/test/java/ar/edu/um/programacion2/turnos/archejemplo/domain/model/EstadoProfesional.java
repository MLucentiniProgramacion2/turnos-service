package ar.edu.um.programacion2.turnos.archejemplo.domain.model;

public enum EstadoProfesional {
    HABILITADO,
    DESHABILITADO;

    public boolean atiende() {
        return this == HABILITADO;
    }
}
