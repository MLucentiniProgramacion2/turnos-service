# CU-07: Procesar el resultado del proceso

## Objetivo

Que turnos aplique al proceso local los eventos de resultado que publica la cátedra (confirmación,
rechazo del teléfono, expiración, proceso inválido y cancelación), **sin duplicar efectos y sin
retroceder** un proceso que ya llegó a un estado final.

## Actores

- **Principal:** Kafka de la cátedra (topic de acciones, `alumnos.turnos.acciones.{groupId}`).

## Disparador

Llega al topic de acciones uno de estos eventos: `AppointmentConfirmed`,
`AdditionalInformationRejected`, `AppointmentProcessExpired`, `AppointmentProcessInvalid` o
`AppointmentCancelled`.

## Precondiciones

- El listener está suscripto al topic de acciones y usa el consumer group recibidos en
  `integration`.

## Flujo principal

1. El listener recibe el mensaje (message key = `reservationProcessId`), lo deserializa ignorando
   campos desconocidos y verifica `eventId`, `eventType` y `reservationProcessId`.
2. En una transacción:
   1. Verifica que el `eventId` no esté en la tabla de eventos procesados (DPT-15).
   2. Busca el proceso local por `reservationProcessId`.
   3. Aplica la transición que corresponde según el tipo de evento y el estado actual (tabla
      siguiente), con los datos del evento.
   4. Registra el `eventId` como procesado.
3. Después del commit de la transacción, commitea el offset.
4. En su próxima consulta (CU-08), la app ve el nuevo estado.

| Evento | Estado de origen válido | Estado nuevo | Datos que se guardan |
| --- | --- | --- | --- |
| `AppointmentConfirmed` | cualquier intermedio | `CONFIRMED` | `reservationId`, instante de confirmación (`occurredAt`) |
| `AdditionalInformationRejected` | `PHONE_SUBMITTED` | `PHONE_REJECTED` | `reason`, `message`, `expiresAt` |
| `AppointmentProcessExpired` | cualquier intermedio | `EXPIRED` | `reason` |
| `AppointmentProcessInvalid` | cualquier intermedio | `INVALID` | `reason` |
| `AppointmentCancelled` | `CONFIRMED` | `CANCELLED` | `reason` (puede ser `null`), instante de cancelación |

## Flujos alternativos

- **1a. Mensaje mal formado o `eventType` desconocido:** no se reintenta. Se registra en el log y
  se commitea el offset (DPT-22). `AdditionalInformationRequested` lo procesa CU-05.
- **2.1a. `eventId` ya procesado** (entrega duplicada): no se repiten efectos y se commitea el
  offset (contrato §18.1).
- **2.2a. No existe un proceso local con ese `reservationProcessId`:** se registra como procesado
  sin efecto, con una advertencia en el log, y se commitea el offset. No se crean procesos ni
  reservas a partir de eventos (DPT-01).
- **2.3a. El proceso está en un estado final** (`CANCELLED`, `EXPIRED`, `INVALID`, `FAILED`):
  ningún evento lo modifica. Se registra como procesado sin efecto (DPT-05, DPT-15).
- **2.3b. El proceso está en `CONFIRMED` y el evento no es `AppointmentCancelled`** (por ejemplo,
  un `AppointmentProcessExpired` o un `AppointmentProcessInvalid` tardío, o la respuesta a un
  teléfono reenviado): se registra como procesado sin efecto. `CONFIRMED` solo sale a
  `CANCELLED` (DPT-05). Si es `AppointmentProcessInvalid`, se deja además una advertencia en el
  log.
- **2.3c. `AdditionalInformationRejected` llega con el proceso en otro estado intermedio** (por
  ejemplo, si falló el guardado de `PHONE_SUBMITTED` en CU-06, flujo 7b): se registra sin efecto.
  El usuario puede volver a enviar el teléfono porque el proceso sigue aceptándolo.
- **2.3d. `AppointmentConfirmed` llega con el proceso en un estado intermedio anterior a
  `PHONE_SUBMITTED`:** se acepta, porque las transiciones que se adelantan son válidas (DPT-05).
- **2.3e. `AppointmentCancelled` llega con el proceso en un estado intermedio:** no debería pasar,
  porque los eventos del mismo proceso comparten partición y llegan en orden. Se registra sin
  efecto y la reconciliación de CU-11 lleva el proceso al estado real.
- **2a. Falla la transacción:** hasta 3 reintentos con backoff de 1 s, 2 s y 4 s. Si se agotan, se
  registra el error y se commitea el offset; el proceso queda en su estado y lo recupera CU-11
  (DPT-22, DPT-09).

## Postcondiciones

- **De éxito:** el proceso refleja el resultado informado por la cátedra y el `eventId` quedó
  registrado en la misma transacción. Una misma reserva nunca queda duplicada: el proceso local es
  uno solo por `reservationProcessId`.
- **De fallo:** el proceso no cambia. Un duplicado o un evento tardío no tiene efecto.

## Decisiones del proyecto

- **DPT-05 (máquina de estados):** solo transiciones hacia adelante; los estados finales
  (`CANCELLED`, `EXPIRED`, `INVALID`, `FAILED`) no cambian; `CONFIRMED` es estable y su única
  salida es `CANCELLED`; `PHONE_REJECTED` no es final.
- **DPT-15 (deduplicación):** `eventId` registrado en la misma transacción que el cambio de estado;
  también los eventos procesados sin efecto; offset commiteado después de persistir.
- **DPT-22 (fallas al procesar):** reintentos limitados y luego commit del offset; la recuperación
  queda a cargo de CU-11.
- **DPT-04:** un rechazo hace que la app vuelva a mostrar el formulario con el motivo.

## Respaldo en las consignas

- Enunciado §4.2: procesar confirmaciones, rechazos, vencimientos y procesos inválidos.
- Enunciado §5 punto 12: reflejar localmente el resultado final del proceso.
- Enunciado §7: pasos 6 y 7; resultados de confirmación, rechazo, expiración, proceso inválido y
  cancelación; converger sin duplicar ni retroceder un estado final.
- Enunciado §8: mensajes duplicados, vencimientos, reintentos limitados.
- Contrato §15.1: entrega al menos una vez, `eventId` como clave de idempotencia, orden por message
  key.
- Contrato §15.6 a §15.10: `AppointmentConfirmed`, `AdditionalInformationRejected`,
  `AppointmentProcessExpired`, `AppointmentProcessInvalid`, `AppointmentCancelled`.
- Contrato §18.1 y §18.3: mensaje duplicado; un evento tardío no reabre un estado final.
