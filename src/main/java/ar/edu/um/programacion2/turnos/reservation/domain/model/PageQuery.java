package ar.edu.um.programacion2.turnos.reservation.domain.model;

/**
 * Página pedida de un resultado: número (desde 0) y tamaño. Como en el catálogo, es un tipo propio y no el
 * {@code Pageable} de Spring Data: el dominio no depende de frameworks (ArchUnit). El adaptador de persistencia lo traduce.
 * <p>
 * Los máximos evitan que una sola consulta traiga todo de una vez ({@link #MAX_SIZE}) o pida un desplazamiento absurdo
 * ({@link #MAX_NUMBER}). Cada endpoint puede pedir un tamaño máximo menor.
 *
 * @param number número de página, desde 0 hasta {@link #MAX_NUMBER}
 * @param size cantidad de elementos por página, de 1 a {@link #MAX_SIZE}
 */
public record PageQuery(int number, int size) {
    public static final int MAX_NUMBER = 10_000;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        if (number < 0 || number > MAX_NUMBER) {
            throw new IllegalArgumentException("El número de página tiene que estar entre 0 y " + MAX_NUMBER + ": " + number);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("El tamaño de página tiene que estar entre 1 y " + MAX_SIZE + ": " + size);
        }
    }
}
