# CU-06: Informar el teléfono

## Objetivo

Que el usuario informe su teléfono para un proceso propio y turnos lo envíe a la cátedra como
`AdditionalInformationSubmitted`. Sirve también para reintentar después de un rechazo, mientras no
haya pasado `expiresAt`.

## Actores

- **Principal:** Usuario final, desde la app KMP.
- **Secundario:** Kafka de la cátedra (topic de teléfono, `catedra.turnos.telefono.{groupId}`).

## Disparador

El usuario completa el formulario de teléfono, que la app muestra cuando el proceso está en
`PHONE_REQUESTED` o `PHONE_REJECTED` (CU-08).

## Precondiciones

- La app envía un JWT de usuario válido (CU-02).
- El proceso tiene `holdId`, `reservationProcessId` y `requestEventId` guardados (CU-04 y CU-05).

## Flujo principal

1. La app envía el identificador local del proceso y el teléfono.
2. Turnos valida el token y busca el proceso por su identificador **y** por el `id` del usuario
   autenticado como dueño (DPT-21).
3. Turnos verifica que el proceso esté en `PHONE_REQUESTED` o `PHONE_REJECTED` y que no haya
   pasado `expiresAt`.
4. Turnos normaliza el teléfono igual que la cátedra (quita espacios, guiones, paréntesis y puntos)
   y verifica que cumpla `^\+?[0-9]{7,15}$`.
5. Turnos arma el evento `AdditionalInformationSubmitted` con:
   - un `eventId` **nuevo**;
   - `eventType=AdditionalInformationSubmitted` y `schemaVersion=1` exactos;
   - el `groupId` de `integration`;
   - `reservationProcessId`, `holdId` y `requestEventId` guardados;
   - `information.phoneNumber` con el teléfono normalizado;
   - `occurredAt` con el instante actual en UTC.
6. Turnos publica el evento en el topic de teléfono de `integration`, con message key =
   `reservationProcessId`, y espera la confirmación del broker.
7. Turnos guarda el teléfono y el `eventId` enviado en el proceso (DPT-15) y lo pasa a
   `PHONE_SUBMITTED`.
8. Turnos responde `202 Accepted` con el estado del proceso. La app sigue consultando (CU-08) hasta
   ver el resultado (CU-07).

## Flujos alternativos

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. El proceso no existe o es de otro usuario:** `404`, sin revelar si existe (DPT-19).
- **3a. El proceso todavía no tiene el pedido de teléfono** (`CREATING_HOLD`, `HOLD_CREATED`,
  `WAITING_FOR_PHONE_REQUEST`): `409`, indicando que todavía no se pidió el teléfono.
- **3b. El proceso está en `PHONE_SUBMITTED`:** `409`, indicando que el teléfono ya se envió y que
  hay que esperar el resultado.
- **3c. El proceso está en `CONFIRMED` o en un estado final:** `409` con el estado actual.
- **3d. Pasó `expiresAt`:** `409`, indicando que venció el tiempo. El proceso no se modifica acá:
  lo cierra el evento `AppointmentProcessExpired` (CU-07) o la reconciliación (CU-11). Conservarlo
  localmente no extiende el hold (contrato §9).
- **4a. Teléfono inválido:** `400` en `problem+json`. No se envía nada y el proceso no cambia.
- **6a. Kafka no confirma la publicación** (error o timeout del productor): el proceso no cambia y
  se responde `503`. El usuario puede volver a enviar, y cada envío lleva un `eventId` nuevo. Si
  el primer envío sí había llegado, la cátedra procesa los dos; cualquier resultado posterior a un
  estado final o a `CONFIRMED` se ignora (DPT-05).
- **7a. El resultado llegó por Kafka antes de guardar `PHONE_SUBMITTED`** (por ejemplo, el proceso
  ya está en `CONFIRMED`): no se retrocede (DPT-05). Se guarda solo el `eventId` enviado y se
  responde con el estado actual.
- **7b. Falla el guardado después de publicar:** el evento ya está en la cátedra. El proceso queda
  en `PHONE_REQUESTED` o `PHONE_REJECTED` y avanza cuando llegue el resultado, porque la máquina de
  estados acepta transiciones que se adelantan (DPT-05). Se registra el error y se responde
  `500`.

## Postcondiciones

- **De éxito:** el evento quedó publicado con un `eventId` nuevo y el proceso está en
  `PHONE_SUBMITTED`, con el `eventId` enviado guardado para trazabilidad.
- **De fallo:** el proceso conserva su estado. Mientras no pase `expiresAt`, el usuario puede
  volver a intentar.

## Decisiones del proyecto

- **DPT-04 (cómo se entera la app):** un rechazo no es final. La app vuelve a mostrar el formulario
  con el motivo y sigue consultando; este caso se repite con un `eventId` nuevo.
- **DPT-05 (máquina de estados):** `PHONE_REQUESTED` y `PHONE_REJECTED` pasan a `PHONE_SUBMITTED`;
  no se retrocede si el resultado llegó antes.
- **DPT-15:** el `eventId` del último envío se guarda en el proceso; la tabla de eventos procesados
  es solo para eventos recibidos.
- **DPT-19 (recursos ajenos):** un proceso de otro usuario responde `404`.
- **DPT-21 (dueño):** el proceso se busca por el `id` del usuario autenticado, nunca por un ID
  enviado por el cliente.

## Respaldo en las consignas

- Enunciado §5 punto 11: enviar la respuesta a la solicitud de información adicional.
- Enunciado §7: paso 5 del proceso de reserva; conservar el hold localmente no extiende su
  vigencia.
- Enunciado §9: impedir que un usuario modifique procesos ajenos; validar los datos en cada límite.
- Contrato §15.5: `AdditionalInformationSubmitted`, campos obligatorios, valores exactos de
  `eventType` y `schemaVersion`, message key, normalización y formato del teléfono.
- Contrato §15.7: después de un rechazo, puede enviarse otro evento válido con un `eventId` nuevo
  hasta `expiresAt`.
