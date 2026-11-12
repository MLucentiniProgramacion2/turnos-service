package ar.edu.um.programacion2.turnos.archejemplo.infrastructure;

import ar.edu.um.programacion2.turnos.archvecina.domain.model.DatoDeOtraFeature;

/** Depende de otra feature ({@code archvecina}): la detecta {@code HexagonalArchitectureTest.featureNoDependeDe}. */
public class FeatureQueUsaOtraFeature {

    public String leer(DatoDeOtraFeature dato) {
        return dato.valor();
    }
}
