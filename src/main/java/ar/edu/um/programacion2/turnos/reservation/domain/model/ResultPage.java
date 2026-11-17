package ar.edu.um.programacion2.turnos.reservation.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Una página de un resultado, con el total de elementos que cumplen los filtros (no solo los de esta página). Como en el
 * catálogo: tipo propio y no el {@code Page} de Spring Data. Una página fuera de rango es válida: viene vacía y con el
 * total correcto.
 *
 * @param items los elementos de esta página, en el orden del resultado
 * @param number número de página, desde 0
 * @param size tamaño de página pedido (la última puede tener menos)
 * @param totalElements total de elementos que cumplen los filtros
 */
public record ResultPage<T>(List<T> items, int number, int size, long totalElements) {
    public ResultPage {
        items = List.copyOf(Objects.requireNonNull(items, "Faltan los elementos de la página"));
        if (number < 0) {
            throw new IllegalArgumentException("El número de página no puede ser negativo: " + number);
        }
        if (size < 1) {
            throw new IllegalArgumentException("El tamaño de página tiene que ser positivo: " + size);
        }
        if (items.size() > size) {
            throw new IllegalArgumentException("La página tiene " + items.size() + " elementos y su tamaño es " + size);
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("El total no puede ser negativo: " + totalElements);
        }
    }

    /** Cantidad de páginas: 0 si no hay elementos; la última puede estar incompleta. */
    public int totalPages() {
        return Math.toIntExact((totalElements + size - 1) / size);
    }

    /** La misma página con otros elementos (por ejemplo, los procesos ya convergidos), en el mismo orden. */
    public <R> ResultPage<R> withItems(List<R> newItems) {
        return new ResultPage<>(newItems, number, size, totalElements);
    }

    public <R> ResultPage<R> map(Function<T, R> mapper) {
        return withItems(items.stream().map(mapper).toList());
    }
}
