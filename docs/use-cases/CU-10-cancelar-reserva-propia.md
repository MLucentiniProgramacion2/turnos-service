# CU-10: Cancelar una reserva propia

## Objetivo

Que el usuario cancele una reserva **propia** confirmada, de forma segura ante reintentos.

## Actores

- **Principal:** Usuario final, desde la app KMP.
- **Secundario:** API REST de la cátedra
  (`POST /api/appointments/{reservationProcessId}/cancel`).

## Disparador

El usuario elige "Cancelar" sobre una reserva confirmada en "Mis reservas" (CU-09).

## Precondiciones

- La app envía un JWT de usuario válido (CU-02).

## Flujo principal

1. La app envía el identificador local del proceso y, opcionalmente, un motivo.
2. Turnos valida el token y el motivo (hasta 500 caracteres).
3. Turnos busca el proceso por su identificador **y** por el `id` del usuario autenticado como
   dueño (DPT-21).
4. Turnos verifica que el proceso esté en `CONFIRMED`.
5. Turnos pide a la cátedra `POST /api/appointments/{reservationProcessId}/cancel` con el motivo.
6. La cátedra responde `200` con `status=CANCELLED` y `cancelledAt`.
7. Turnos pasa el proceso a `CANCELLED` y guarda `cancelledAt` y el motivo.
8. Turnos responde `200` con el proceso cancelado.
9. Más tarde llega `AppointmentCancelled` por Kafka. CU-07 lo registra como procesado sin efecto,
   porque el proceso ya está en `CANCELLED`.

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Motivo de más de 500 caracteres:** `400` en `problem+json`.
- **3a. El proceso no existe o es de otro usuario:** `404`, sin revelar si existe (DPT-19).
- **4a. El proceso ya está en `CANCELLED`:** no se llama a la cátedra. Se responde `200` con el
  estado actual: la cancelación es idempotente.
- **4b. El proceso está en un estado intermedio u otro estado final:** `409`, indicando que solo
  se pueden cancelar reservas confirmadas.
- **5a. Timeout o 5xx de la cátedra:** **sin reintentos**, porque el usuario está esperando
  (DPT-08). Se responde `503` y el proceso queda en `CONFIRMED`. Que haya timeout no prueba que la
  cancelación falló: puede haberse aplicado en la cátedra aunque el usuario reciba `503`. El
  estado local converge por dos caminos:
  - el evento `AppointmentCancelled` (CU-07);
  - la consulta de "Mis reservas" a la cátedra (CU-09), que pasa el proceso a `CANCELLED` si la
    cátedra lo informa así.

  Si el usuario reintenta, el contrato garantiza que cancelar una reserva ya `CANCELLED` es
  idempotente y devuelve su estado actual, así que el reintento es seguro (contrato §12 y
  §18.4).
- **6a. `409 RESERVATION_NOT_CANCELLABLE`** (en la cátedra la reserva no está en `CONFIRMED` ni en
  `CANCELLED`): se registra en el log como discrepancia y se responde `409`. El proceso local no
  cambia; si corresponde, lo corrige CU-09 o CU-11.
- **6b. `404 RESERVATION_NOT_FOUND`, `403 RESERVATION_OWNER_MISMATCH`, `401` o
  `403 STUDENT_INTEGRATION_NOT_FOUND`:** son inconsistencias propias o de configuración de la
  cuenta técnica. Se registran en el log y se responde `500` o `503` genérico, sin exponer el
  motivo técnico (DPT-18). El proceso no cambia.
- **7a. `AppointmentCancelled` llegó antes que el `200`** y el proceso ya está en `CANCELLED`:
  no se modifica y se responde con el estado actual (DPT-05).

## Postcondiciones

- **De éxito:** la reserva está cancelada en la cátedra y el proceso local está en `CANCELLED`, un
  estado final.
- **De fallo:** el proceso conserva su estado. Si la cancelación se aplicó en la cátedra pese al
  error, el proceso converge a `CANCELLED` por CU-07 o CU-09. Nunca se cancela una reserva ajena.

## Decisiones del proyecto

- **DPT-05 (máquina de estados):** solo `CONFIRMED` pasa a `CANCELLED`, que es final.
- **DPT-08 (reintentos):** las llamadas que el usuario espera no se reintentan; la idempotencia
  del contrato hace seguro que el usuario reintente.
- **DPT-18 (errores):** los errores de configuración de la cuenta técnica no se reenvían a la app.
- **DPT-19 (recursos ajenos):** una reserva de otro usuario responde `404`.
- **DPT-21 (dueño):** el proceso se busca por el `id` del usuario autenticado, nunca por un ID
  enviado por el cliente.
- **DPT-23:** CU-09 cruza con la cátedra y aplica la cancelación si se perdió la respuesta.

## Respaldo en las consignas

- Enunciado §4.2: cancelar reservas confirmadas.
- Enunciado §5 punto 14: cada usuario cancela una reserva propia confirmada.
- Enunciado §8: repetición de una operación por timeout o respuesta perdida.
- Enunciado §9: impedir que un usuario cancele reservas ajenas.
- Contrato §12: cancelación, motivo opcional de hasta 500 caracteres, idempotencia sobre
  `CANCELLED`, publicación de `AppointmentCancelled`.
- Contrato §13.1: `RESERVATION_NOT_FOUND`, `RESERVATION_OWNER_MISMATCH`,
  `RESERVATION_NOT_CANCELLABLE`, `STUDENT_INTEGRATION_NOT_FOUND`.
- Contrato §15.10 y §18.4: `AppointmentCancelled`; un timeout no prueba que la operación falló.
