# CU-11: Reconciliar procesos pendientes

## Objetivo

Llevar a un estado correcto los procesos que quedaron en un estado intermedio: los interrumpidos
por un reinicio, los que tuvieron un timeout con la cátedra y los que nunca recibieron su resultado
por Kafka. Para eso usa los identificadores persistidos y `GET /api/appointments`, sin dejar
procesos que nunca se cierran y sin duplicar reservas.

## Actores

- **Principal:** Arranque del servicio, Temporizador.
- **Secundario:** API REST de la cátedra (`GET /api/appointments` y confirm del hold).

## Disparador

- El arranque del servicio.
- El temporizador, cada N segundos (variable de entorno, por defecto 60 s).
- Indirectamente, CU-04 (timeout del confirm, DPT-07) y los listeners (DPT-22): dejan el proceso en
  su estado y lo toma la próxima ejecución.

## Precondiciones

- No hay otra ejecución de CU-11 en curso (DPT-09).

## Flujo principal

1. Se dispara CU-11 y se verifica que no haya otra ejecución en curso.
2. Turnos lee los procesos en estado **intermedio** (`CREATING_HOLD`, `HOLD_CREATED`,
   `WAITING_FOR_PHONE_REQUEST`, `PHONE_REQUESTED`, `PHONE_SUBMITTED`, `PHONE_REJECTED`).
   `CONFIRMED` y los estados finales no se revisan.
3. Para cada proceso, aplica la acción que corresponde a su estado (tabla siguiente).
4. Cuando hace falta consultar a la cátedra, pide
   `GET /api/appointments?externalPatientId=user-{id}&from=<fecha>&to=<fecha>`, con la fecha del
   turno, recorriendo todas las páginas, y busca el `reservationProcessId` del proceso.
5. Cada cambio de estado se guarda con las reglas de la máquina de estados (DPT-05) y bloqueo
   optimista. Se registra en el log con el proceso, el estado anterior, el nuevo y el motivo.
6. Al terminar, registra un resumen: procesos revisados, cambiados y con error.

| Estado local | Condición | Acción |
| --- | --- | --- |
| `CREATING_HOLD` | sin cambios hace más de 1 minuto (configurable, DPT-24) | pasa a `FAILED` con motivo "resultado de la creación del hold desconocido" (DPT-06) |
| `HOLD_CREATED` | sin cambios hace más de 1 minuto (configurable, DPT-24) | reconciliación del confirm (flujo 3a) |
| `WAITING_FOR_PHONE_REQUEST`, `PHONE_REQUESTED`, `PHONE_SUBMITTED`, `PHONE_REJECTED` | antes de `expiresAt` + 1 minuto | nada: la cátedra todavía puede enviar eventos |
| ídem | después de `expiresAt` + 1 minuto | reconciliación por vencimiento (flujo 3b) |

## Flujos alternativos

- **1a. Ya hay una ejecución en curso:** esta se saltea y se registra en el log (DPT-09).
- **3a. Reconciliación del confirm (`HOLD_CREATED`)** (DPT-07):
  - la cátedra lo tiene en `PHONE_PENDING`: el confirm se aplicó y el proceso pasa a
    `WAITING_FOR_PHONE_REQUEST`;
  - la cátedra lo tiene en `CONFIRMED` o `CANCELLED`: pasa a ese estado;
  - la cátedra no lo tiene y **no** pasó `expiresAt`: el confirm no se aplicó, así que se reintenta
    `POST /api/appointment-holds/{holdId}/confirm` con los datos guardados:
    - `202` o `409 RESERVATION_PROCESS_ALREADY_CONFIRMED`: pasa a `WAITING_FOR_PHONE_REQUEST`;
    - `404 HOLD_EXPIRED`: pasa a `EXPIRED`;
    - otro error funcional (`SLOT_ALREADY_RESERVED`, `INVALID_SLOT`, `PROFESSIONAL_NOT_FOUND`,
      `HOLD_NOT_FOUND`, `HOLD_OWNER_MISMATCH`, `EXTERNAL_PATIENT_MISMATCH`): pasa a `FAILED` y se
      registra en el log;
  - la cátedra no lo tiene y pasó `expiresAt`: no se reintenta. Pasa a `EXPIRED` una vez superado
    `expiresAt` + 1 minuto; antes, se espera a la próxima ejecución;
  - la cátedra lo tiene en `FAILED`: se aplica la regla de 3b.
- **3b. Reconciliación por vencimiento** (DPT-09), pasado `expiresAt` + 1 minuto:
  - cátedra `CONFIRMED` → `CONFIRMED`;
  - cátedra `CANCELLED` → `CANCELLED`;
  - cátedra `FAILED` o no encontrado → `EXPIRED`;
  - cátedra `PHONE_PENDING` → se espera; si sigue así después de `expiresAt` + 10 minutos
    (configurable), pasa a `EXPIRED` localmente con una advertencia en el log, para no dejar un
    proceso que nunca se cierra.
- **4a. La cátedra no responde o responde 5xx:** hasta 3 reintentos con backoff de 1 s, 2 s y 4 s,
  porque es una tarea en segundo plano (DPT-08). Si se agotan, el proceso queda en su estado, se
  registra el error y lo toma la próxima ejecución. El confirm nunca se reintenta después de
  `expiresAt`.
- **4b. La cátedra responde `401` o `403 STUDENT_INTEGRATION_NOT_FOUND`:** problema de
  configuración de la cuenta técnica. Se registra el error y se corta la ejecución: los demás
  procesos fallarían igual. Los procesos quedan para la próxima ejecución.
- **5a. Un evento Kafka o una consulta de CU-09 movió el proceso mientras tanto:** el bloqueo
  optimista rechaza el cambio de CU-11. El proceso se vuelve a leer y se reevalúa con su estado
  nuevo, que puede ya no requerir acción (DPT-09).

## Postcondiciones

- **De éxito:** cada proceso revisado quedó en el estado que corresponde a lo que informa la
  cátedra, o sigue esperando con un plazo acotado. Ningún proceso queda indefinidamente en un
  estado intermedio y no se crearon reservas duplicadas.
- **De fallo:** los procesos que no se pudieron reconciliar conservan su estado y se reintentan en
  la próxima ejecución. Los errores quedan registrados.

## Decisiones del proyecto

- **DPT-06 (timeout al crear el hold):** un proceso en `CREATING_HOLD` no se puede retomar, porque
  no hay `holdId`. Pasa a `FAILED`.
- **DPT-07 (timeout al confirmar):** antes de reintentar el confirm se reconcilia con
  `GET /api/appointments`; `RESERVATION_PROCESS_ALREADY_CONFIRMED` se toma como éxito.
- **DPT-08 (reintentos en segundo plano):** hasta 3 reintentos con backoff de 1 s, 2 s y 4 s, nunca
  después de `expiresAt`; el POST del hold nunca se reintenta.
- **DPT-09 (recuperación y temporizador):** se ejecuta al arrancar y cada 60 s (configurable), una
  ejecución a la vez, solo sobre estados intermedios. Margen de 1 minuto sobre `expiresAt`,
  coincidente con el corte del polling de la app (DPT-04). Espera máxima de 10 minutos para
  `PHONE_PENDING`. Concurrencia resuelta con la máquina de estados y bloqueo optimista. Los eventos
  Kafka no necesitan acción extra al reiniciar: el offset se commitea después de persistir.
- **DPT-05 (máquina de estados):** la reconciliación solo aplica transiciones hacia adelante. Es el
  único camino por el que un proceso intermedio puede pasar directo a `CANCELLED`.
- **DPT-22 (fallas en listeners):** un evento que no se pudo persistir lo recupera este caso.
- **DPT-24 (antigüedad mínima):** CU-11 solo toma `CREATING_HOLD` y `HOLD_CREATED` si el proceso
  lleva más de 1 minuto sin cambios (configurable). Evita la carrera con CU-04, que puede estar
  esperando la respuesta del hold o del confirm (hasta 7 s por DPT-08): sin este resguardo, CU-11
  podría marcar `FAILED` un proceso cuyo hold está por crearse, dejando un hold huérfano y un error
  falso para el usuario.

## Respaldo en las consignas

- Enunciado §4.2: recuperar procesos pendientes o interrumpidos.
- Enunciado §8: reinicio con operaciones pendientes, interrupción de la conexión con la cátedra,
  vencimiento de holds y procesos, reintentos limitados, ningún estado local imposible de
  recuperar.
- Contrato §11: `GET /api/appointments`, filtros y paginación.
- Contrato §10 y §13.1: confirm del hold y sus errores.
- Contrato §18.3 y §18.4: hold o proceso vencido; un timeout no prueba que la operación falló,
  reconciliar con los IDs persistidos y reintentos limitados y observables.
