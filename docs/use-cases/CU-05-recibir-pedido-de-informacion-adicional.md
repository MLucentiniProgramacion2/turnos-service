# CU-05: Recibir el pedido de información adicional

## Objetivo

Que turnos registre que la cátedra pidió el teléfono para un proceso: guarda el `eventId` del pedido
(que después se envía como `requestEventId`) y deja el proceso en `PHONE_REQUESTED`, para que la app
muestre el formulario.

## Actores

- **Principal:** Kafka de la cátedra (topic de acciones, `alumnos.turnos.acciones.{groupId}`).

## Disparador

Llega un mensaje `AdditionalInformationRequested` al topic de acciones, después de que CU-04
confirmó inicialmente el hold.

## Precondiciones

- El listener está suscripto al topic de acciones y usa el consumer group recibidos en
  `integration` (no se fijan en el código).
- El proceso existe localmente con su `reservationProcessId`, porque CU-04 guarda los datos del 201
  **antes** de llamar al confirm, y la cátedra publica este evento recién después del confirm.

## Flujo principal

1. El listener recibe el mensaje (message key = `reservationProcessId`) y lo deserializa, ignorando
   los campos desconocidos (contrato §15.1).
2. Verifica que tenga `eventId`, `eventType=AdditionalInformationRequested`,
   `reservationProcessId`, `holdId` y `expiresAt`.
3. En una transacción:
   1. Verifica que el `eventId` no esté en la tabla de eventos procesados (DPT-15).
   2. Busca el proceso local por `reservationProcessId`.
   3. Verifica que el proceso esté en `HOLD_CREATED` o en `WAITING_FOR_PHONE_REQUEST`.
   4. Guarda el `eventId` del evento como `requestEventId`, el `expiresAt` informado y el `message`
      para la app, y pasa el proceso a `PHONE_REQUESTED`.
   5. Registra el `eventId` como procesado.
4. Después del commit de la transacción, commitea el offset.
5. En su próxima consulta (CU-08, DPT-04), la app ve `PHONE_REQUESTED` y muestra el formulario de
   teléfono con la cuenta regresiva hasta `expiresAt`.

## Flujos alternativos

- **2a. Mensaje mal formado o sin campos obligatorios:** no se reintenta. Se registra en el log y
  se commitea el offset (DPT-22).
- **3.1a. `eventId` ya procesado** (entrega duplicada): no se repiten efectos y se commitea el
  offset (contrato §18.1).
- **3.2a. No existe un proceso local con ese `reservationProcessId`** (por ejemplo, después de un
  reseteo de la base local): se registra el evento como procesado sin efecto, se deja una
  advertencia en el log y se commitea el offset. No se crea un proceso nuevo: la propiedad sale
  solo de la tabla local (DPT-01).
- **3.3a. El proceso estaba en `HOLD_CREATED`** (el evento llegó antes que el `202` del confirm):
  pasa igual a `PHONE_REQUESTED`. El `202` que llegue después se ignora (DPT-05).
- **3.3b. El proceso está en un estado final o en `CONFIRMED`:** el evento se registra como
  procesado sin efecto. Un evento tardío no reabre un proceso (DPT-05).
- **3.3c. El proceso ya está en `PHONE_REQUESTED`, `PHONE_SUBMITTED` o `PHONE_REJECTED`** (otro
  pedido con un `eventId` distinto, situación que el contrato no describe): se registra como
  procesado sin efecto y se deja una advertencia en el log.
- **3a. Falla la transacción** (por ejemplo, la base no responde): hasta 3 reintentos con backoff
  de 1 s, 2 s y 4 s. Si se agotan, se registra el error y se commitea el offset. El proceso queda
  en su estado y lo recupera CU-11 (DPT-22, DPT-09).

## Postcondiciones

- **De éxito:** el proceso está en `PHONE_REQUESTED` con `requestEventId` y `expiresAt` guardados,
  y el `eventId` quedó registrado como procesado en la misma transacción.
- **De fallo:** el proceso no cambia. Un duplicado o un evento tardío no tiene efecto. Un evento
  que no se pudo persistir queda cubierto por la reconciliación de CU-11.

## Decisiones del proyecto

- **DPT-05 (máquina de estados):** `HOLD_CREATED` y `WAITING_FOR_PHONE_REQUEST` pasan a
  `PHONE_REQUESTED`; ningún evento modifica un estado final ni saca a `CONFIRMED` de su estado.
- **DPT-15 (deduplicación):** tabla de eventos procesados con `eventId` único, escrita en la misma
  transacción que el cambio de estado; el offset se commitea después. También se registran los
  eventos procesados sin efecto, para que sus duplicados no se reevalúen.
- **DPT-22 (fallas al procesar):** reintentos limitados y luego commit del offset; los mensajes mal
  formados no se reintentan.
- **DPT-04:** la app se entera por consulta periódica (CU-08), no por un aviso desde turnos.

## Respaldo en las consignas

- Enunciado §5 punto 11: recibir la solicitud de información adicional por Kafka.
- Enunciado §7: paso 4 del proceso de reserva; REST y Kafka pueden llegar en distinto orden.
- Enunciado §8: mensajes duplicados, reinicios, reintentos limitados.
- Contrato §15.1: entrega al menos una vez, `eventId` como clave de idempotencia, ignorar campos
  desconocidos.
- Contrato §15.4: `AdditionalInformationRequested`; conservar `eventId` para enviarlo como
  `requestEventId`.
- Contrato §17 y §18.1: diagrama de reserva; mensaje duplicado.
