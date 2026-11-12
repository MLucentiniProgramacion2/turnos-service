# CU-04: Iniciar una reserva

## Objetivo

Que el usuario reserve un turno con **una sola acción**: turnos valida el pedido con la información
vigente del catálogo, crea el hold en la cátedra y lo confirma inicialmente, dejando el proceso
listo para recibir el pedido de teléfono.

## Actores

- **Principal:** Usuario final, desde la app KMP.
- **Secundarios:** Servicio de catálogo (CU-07 del catálogo), API REST de la cátedra (holds).

## Disparador

En la pantalla de resumen de la app, el usuario toca "Reservar" sobre un turno elegido en CU-03.

## Precondiciones

- La app envía un JWT de usuario válido (CU-02).
- El perfil del usuario tiene `firstName` y `lastName` válidos (garantizado por DPT-02).
- La revisión previa la hace la app en su pantalla de resumen, sin crear el hold (DPT-03).

## Flujo principal

1. La app envía el ID del profesional, la fecha y la hora de inicio del turno.
2. Turnos valida el token y obtiene el `id` interno del usuario (DPT-21). Valida también los datos:
   fecha `yyyy-MM-dd`, hora `HH:mm` o `HH:mm:ss`, fecha dentro del horizonte de 60 días (DPT-10) y
   turno que todavía no empezó.
3. Turnos busca si el usuario ya tiene un proceso **intermedio** o `CONFIRMED` para ese mismo
   profesional, fecha y hora (DPT-17). No hay ninguno.
4. Turnos busca si el usuario tiene un proceso intermedio para **otro** turno (DPT-17). No hay
   ninguno.
5. Turnos pide al catálogo la agenda vigente del profesional (CU-07 del catálogo) con su token
   técnico de servicio y el login del usuario en `X-User-Login` (DPT-16).
6. Turnos verifica que el profesional esté habilitado y que el turno pedido sea un slot de un
   horario semanal habilitado: mismo día de la semana, hora de inicio alineada a
   `slotDurationMinutes` desde `startTime` y fin antes o justo en `endTime`.
7. Turnos guarda el proceso en estado `CREATING_HOLD` (DPT-03) con:
   - el `id` del usuario como dueño y su login para mostrar (DPT-21);
   - profesional, fecha, hora de inicio y hora de fin;
   - los datos descriptivos históricos del profesional (DPT-12);
   - los nombres del paciente tomados del perfil (DPT-02).

   Un índice único parcial garantiza un solo proceso intermedio por usuario (DPT-17).
8. Turnos pide a la cátedra `POST /api/appointment-holds` con `professionalId`, `date`,
   `startTime` y `externalPatientId = "user-" + id` (DPT-01).
9. La cátedra responde `201` con `holdId`, `reservationProcessId`, `status=HELD` y `expiresAt`.
10. Turnos guarda esos datos y pasa el proceso a `HOLD_CREATED` **antes** de llamar al confirm.
11. Turnos pide a la cátedra `POST /api/appointment-holds/{holdId}/confirm` con `externalPatientId`,
    `patientFirstName` y `patientLastName`.
12. La cátedra responde `202` con `status=WAITING_FOR_PHONE` y `expiresAt`.
13. Turnos pasa el proceso a `WAITING_FOR_PHONE_REQUEST`.
14. Turnos responde `202 Accepted` con el identificador local del proceso, su estado, `expiresAt` y
    los datos del turno. La app abre la pantalla del proceso, muestra la cuenta regresiva y empieza
    a consultar su estado (CU-08, DPT-04).

## Flujos alternativos

**Validación y control de duplicados**

- **2a. Sin token, token inválido o vencido:** `401`.
- **2b. Datos con formato inválido, fecha fuera del horizonte o turno ya iniciado:** `400` en
  `problem+json`. No se crea nada.
- **3a. Ya existe un proceso intermedio o `CONFIRMED` del usuario para el mismo turno:** no se crea
  nada ni se llama a la cátedra. Se responde `200` con ese proceso en su estado actual. Así,
  repetir el pedido (doble toque o reintento de la app por respuesta perdida) es seguro (DPT-17).
- **4a. El usuario tiene un proceso intermedio para otro turno:** `409`, indicando que ya tiene una
  reserva en curso (DPT-17).

**Catálogo**

- **5a. El catálogo no responde, responde 5xx o responde `503` por base vacía:** `503`, sin
  reintentos y **sin guardar el proceso** ni llamar a la cátedra (DPT-11 y DPT-13).
- **6a. El profesional no existe en el catálogo:** `404`. No se crea nada.
- **6b. El profesional está deshabilitado:** `400`, indicando que el profesional no está
  habilitado. No se crea nada.
- **6c. El turno no corresponde a un horario habilitado:** `400`, indicando que el turno no es
  válido. No se crea nada.

**Alta del proceso**

- **7a. El alta choca con el índice único parcial** (otro pedido del mismo usuario se guardó al
  mismo tiempo): turnos busca el proceso intermedio existente. Si es del mismo turno, sigue como
  3a; si es de otro, sigue como 4a (DPT-17).

**Crear el hold (paso 8)**

- **8a. Timeout o 5xx:** no se reintenta. El proceso pasa a `FAILED` con motivo "resultado de la
  creación del hold desconocido" y se responde `503`, con un mensaje que avisa que el horario puede
  quedar bloqueado unos minutos y que pruebe con otro turno o más tarde (DPT-06).
- **8b. `409 SLOT_ALREADY_HELD` o `409 SLOT_ALREADY_RESERVED`:** el proceso pasa a `FAILED` y se
  responde `409`, indicando que el turno ya no está disponible.
- **8c. `400 INVALID_SLOT` o `400 PROFESSIONAL_DISABLED`** (la copia del catálogo iba atrasada
  respecto de la cátedra, DP-03 del catálogo): el proceso pasa a `FAILED` y se responde `400`,
  indicando que el turno o el profesional ya no están habilitados.
- **8d. `404 PROFESSIONAL_NOT_FOUND`:** el proceso pasa a `FAILED` y se responde `404`.
- **8e. `400 VALIDATION_ERROR`, `401` o `403 STUDENT_INTEGRATION_NOT_FOUND`** (error propio o de
  configuración de la cuenta técnica): el proceso pasa a `FAILED`, se registra en el log y se
  responde `500` o `503` genérico, sin exponer el motivo técnico (DPT-18).

**Confirmar el hold (paso 11)**

- **11a. Timeout o 5xx:** el proceso queda en `HOLD_CREATED` y se responde igual `202` con ese
  estado. La app empieza a consultar (DPT-04) y la reconciliación la hace CU-11 (DPT-07):
  - busca el `reservationProcessId` en `GET /api/appointments` y, si el confirm no se aplicó, lo
    reintenta solo antes de `expiresAt`.
- **11b. `404 HOLD_EXPIRED`:** el proceso pasa a `EXPIRED` (contrato §18.3) y se responde `409`,
  indicando que venció el tiempo para reservar.
- **11c. `409 SLOT_ALREADY_RESERVED`, `400 INVALID_SLOT` o `404 PROFESSIONAL_NOT_FOUND`:** el
  proceso pasa a `FAILED` y se responde como en 8b, 8c y 8d.
- **11d. `409 RESERVATION_PROCESS_ALREADY_CONFIRMED`:** el confirm ya estaba aplicado. Se trata
  como éxito y se sigue en el paso 13.
- **11e. `404 HOLD_NOT_FOUND`, `403 HOLD_OWNER_MISMATCH`, `400 EXTERNAL_PATIENT_MISMATCH`,
  `400 VALIDATION_ERROR`, `401` o `403 STUDENT_INTEGRATION_NOT_FOUND`:** son inconsistencias
  propias o de configuración, que no deberían ocurrir. El proceso pasa a `FAILED`, se registra en
  el log y se responde `500` o `503` genérico (DPT-18).

**Orden REST/Kafka**

- **13a. `AdditionalInformationRequested` llegó antes que el `202`** y el proceso ya está en
  `PHONE_REQUESTED` (CU-05): el `202` se ignora porque sería retroceder (DPT-05). Se responde
  `202` con el estado actual.
- **13b. El proceso ya pasó a un estado final por un evento Kafka** (por ejemplo,
  `AppointmentProcessExpired`): el `202` no lo modifica (DPT-05). Se responde con el estado
  actual.

## Postcondiciones

- **De éxito:** existe un proceso del usuario en `WAITING_FOR_PHONE_REQUEST` o `PHONE_REQUESTED`
  (o en `HOLD_CREATED` si el confirm quedó para reconciliar), con `holdId`, `reservationProcessId`
  y `expiresAt` guardados. La cátedra tiene una reserva `PHONE_PENDING` y va a publicar
  `AdditionalInformationRequested`.
- **De fallo:**
  - si falló antes del paso 7 (validación, catálogo, duplicado), no se crea ningún proceso;
  - si falló después, el proceso queda en `FAILED` o `EXPIRED`, que son estados finales.

  En ningún caso se crea una reserva duplicada.

## Decisiones del proyecto

- **DPT-01 (`externalPatientId`):** se envía `"user-" + id` interno del usuario. La propiedad del
  proceso se determina por la tabla local, nunca por este valor.
- **DPT-02 (nombre del paciente):** `patientFirstName` y `patientLastName` salen del perfil del
  usuario; el cliente no los envía. Se guardan en el proceso para poder reintentar el confirm
  (CU-11).
- **DPT-03 (una sola acción):** hold y confirm se hacen en el mismo pedido. El proceso se guarda en
  `CREATING_HOLD` antes del POST del hold, para cubrir un timeout cuando todavía no hay `holdId`,
  y los datos del 201 se guardan antes del confirm. La revisión la resuelve la app con una pantalla
  de resumen. Motivo: el contrato no permite liberar un hold, así que separar los pasos no da una
  forma real de arrepentirse y solo agrega tiempo para que venza.
- **DPT-05 (máquina de estados):** solo transiciones hacia adelante; un `202` tardío no hace
  retroceder un proceso que ya avanzó por Kafka.
- **DPT-06 (timeout al crear el hold):** no se reintenta. Sin `holdId` no se puede confirmar y la
  cátedra crea la reserva recién con el confirm, así que un hold huérfano vence solo y nunca se
  convierte en reserva. Reintentar sería peor: si el primer POST se aplicó, el reintento recibe
  `SLOT_ALREADY_HELD` contra nuestro propio hold.
- **DPT-07 (timeout al confirmar):** se responde `202` con el proceso en `HOLD_CREATED` y lo
  reconcilia CU-11.
- **DPT-08 (cátedra):** timeouts de 2 s y 5 s. Como el usuario está esperando, ninguna llamada de
  este caso se reintenta. El POST del hold nunca se reintenta; el confirm solo se reintenta en
  segundo plano, después de reconciliar (CU-11).
- **DPT-10 (horizonte):** CU-04 también valida el horizonte de 60 días, para que no se pueda
  saltear CU-03 llamando directo a la API.
- **DPT-11, DPT-13 (catálogo):** sin agenda vigente no se inicia la reserva; sin reintentos hacia
  el catálogo.
- **DPT-12 (datos históricos):** el proceso guarda nombre y apellido del profesional, nombre de la
  categoría y duración del slot tal como los informó el catálogo, solo para mostrar.
- **DPT-16 (autenticación con el catálogo):** token técnico de servicio y `X-User-Login`.
- **DPT-17 (pedido repetido y un proceso intermedio por usuario):** el mismo turno devuelve el
  proceso existente; otro turno con uno en curso responde `409`. La atomicidad la da un índice
  único parcial sobre los estados intermedios.
- **DPT-18 (errores):** los errores de configuración de la cuenta técnica no se reenvían a la app.
- **DPT-21 (dueño):** el proceso guarda el `id` interno del usuario como dueño.

## Respaldo en las consignas

- Enunciado §4.2: antes de iniciar una operación, turnos obtiene del catálogo la información
  vigente; crea y confirma inicialmente holds; cada proceso queda asociado al usuario que lo
  inició; puede guardar datos descriptivos históricos.
- Enunciado §5 puntos 9 y 10: solicitar el hold e iniciar la confirmación por REST.
- Enunciado §7: pasos 1 a 3 del proceso de reserva; el hold tiene duración limitada.
- Enunciado §8: repetición por timeout o respuesta perdida, catálogo no disponible, datos
  inválidos.
- Contrato §2: `externalPatientId` es un identificador estable del usuario.
- Contrato §9 y §10: crear y confirmar el hold, campos y restricciones.
- Contrato §13.1: `PROFESSIONAL_NOT_FOUND`, `PROFESSIONAL_DISABLED`, `INVALID_SLOT`,
  `SLOT_ALREADY_RESERVED`, `SLOT_ALREADY_HELD`, `HOLD_NOT_FOUND`, `HOLD_EXPIRED`,
  `HOLD_OWNER_MISMATCH`, `EXTERNAL_PATIENT_MISMATCH`, `RESERVATION_PROCESS_ALREADY_CONFIRMED`,
  `STUDENT_INTEGRATION_NOT_FOUND`, `VALIDATION_ERROR`.
- Contrato §18.3 y §18.4: hold vencido; un timeout no prueba que la operación falló.
