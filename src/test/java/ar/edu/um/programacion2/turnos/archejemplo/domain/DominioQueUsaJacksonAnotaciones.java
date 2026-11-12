package ar.edu.um.programacion2.turnos.archejemplo.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DominioQueUsaJacksonAnotaciones {

    @JsonProperty("nombre")
    private String nombre;
}
