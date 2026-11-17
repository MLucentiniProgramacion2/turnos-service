# El proceso de reserva: estados, idempotencia y recuperación

Cómo lleva este servicio cada proceso de reserva desde el hold hasta su estado final, y cómo se recupera de
mensajes duplicados, perdidos o fuera de orden, de timeouts y de reinicios. Cubre el punto del enunciado §12
"el procesamiento idempotente y la recuperación ante mensajes duplicados, perdidos o fuera de orden", del lado
de turnos (el del catálogo está en su `docs/sincronizacion.md`).

No repite los casos de uso: los resume y los enlaza. El detalle de cada flujo está en
[CU-04](use-cases/CU-04-iniciar-reserva.md) (iniciar), [CU-05](use-cases/CU-05-recibir-pedido-de-informacion-adicional.md)
(pedido de teléfono), [CU-06](use-cases/CU-06-informar-telefono.md) (teléfono),
[CU-07](use-cases/CU-07-procesar-resultado-del-proceso.md) (resultados), [CU-08](use-cases/CU-08-consultar-estado-de-un-proceso.md)
(estado), [CU-09](use-cases/CU-09-consultar-mis-reservas.md) (mis reservas),
[CU-10](use-cases/CU-10-cancelar-reserva-propia.md) (cancelar) y [CU-11](use-cases/CU-11-reconciliar-procesos-pendientes.md)
(reconciliar). Las decisiones (DPT-xx) están en el [README de casos de uso](use-cases/README.md#decisiones-del-proyecto);
las tablas, en [`modelo-de-datos.md`](modelo-de-datos.md).

## 1. La idea en tres frases

1. **La cátedra tiene la última palabra; turnos guarda su propia foto y la hace converger.** Cada proceso local
   avanza con lo que dicen REST y Kafka, y cuando algo se pierde, la reconciliación le pregunta a la cátedra con
   los identificadores guardados.
2. **Solo se avanza.** La máquina de estados decide; un hecho que no corresponde queda "sin efecto", no es un
   error. Por eso repetir, reordenar o llegar tarde no rompe nada.
3. **Cada escritura relee.** El que guarda vuelve a leer el proceso y vuelve a decidir: si otro (un evento, una
   consulta, la reconciliación) lo movió mientras tanto, gana uno y nadie retrocede (`@Version`).

## 2. Quién cambia un proceso

| Quién | Cuándo | Qué hecho aplica | Caso |
| --- | --- | --- | --- |
| La app, por REST | Inicia la reserva | crea `CREATING_HOLD`; `holdCreated`; `confirmAccepted`; `failed` o `expired` si la cátedra rechaza | CU-04 |
| Kafka (topic de acciones) | `AdditionalInformationRequested` | `phoneRequested` | CU-05 |
| La app, por REST | Informa el teléfono (y lo publica en el topic de teléfono) | `phoneSubmitted` | CU-06 |
| Kafka (topic de acciones) | `AppointmentConfirmed`, `AdditionalInformationRejected`, `AppointmentProcessExpired`, `AppointmentProcessInvalid`, `AppointmentCancelled` | `confirmed`, `phoneRejected`, `expired`, `invalid`, `cancelled` | CU-07 |
| La app, por REST | Abre "Mis reservas" | `confirmed` o `cancelledByReconciliation`, si la cátedra está más adelante | CU-09 |
| La app, por REST | Cancela | `cancelled` | CU-10 |
| El arranque y cada 60 s | Una sola tarea `fixedDelay` con demora inicial 0 | `failed`, `confirmAccepted`, `confirmed`, `cancelledByReconciliation`, `expired` | CU-11 |

Consultar el estado (CU-08) y la consulta de administrador (CU-12) no escriben nunca.

## 3. La máquina de estados

Tres clases de estados (DPT-05): **intermedios** (`CREATING_HOLD`, `HOLD_CREATED`, `WAITING_FOR_PHONE_REQUEST`,
`PHONE_REQUESTED`, `PHONE_SUBMITTED`, `PHONE_REJECTED`), **estable** (`CONFIRMED`, que solo sale a `CANCELLED`)
y **finales** (`CANCELLED`, `EXPIRED`, `INVALID`, `FAILED`). El diagrama está en el
[README de casos de uso](use-cases/README.md#máquina-de-estados-del-proceso-de-reserva).

Cada hecho es un método de `ReservationProcess` que aplica las reglas y devuelve `Applied` o `NoEffect` con su
motivo. Qué hecho mueve cada estado:

| Hecho | Desde | Hacia | Si no corresponde |
| --- | --- | --- | --- |
| `holdCreated` | `CREATING_HOLD` | `HOLD_CREATED` | error de programación (el `POST` del hold nunca se repite) |
| `confirmAccepted` | `HOLD_CREATED` | `WAITING_FOR_PHONE_REQUEST` | `NOT_FORWARD` si el pedido de teléfono llegó antes |
| `phoneRequested` | `HOLD_CREATED`, `WAITING_FOR_PHONE_REQUEST` | `PHONE_REQUESTED` | `NOT_FORWARD` (un segundo pedido) |
| `phoneSubmitted` | `PHONE_REQUESTED`, `PHONE_REJECTED` | `PHONE_SUBMITTED` | guarda igual el `eventId` enviado; error sin pedido |
| `phoneRejected` | `PHONE_SUBMITTED` | `PHONE_REJECTED` | `NOT_APPLICABLE` en otro intermedio |
| `confirmed` | cualquier intermedio con hold | `CONFIRMED` | — |
| `expired`, `invalid` | cualquier intermedio con hold | `EXPIRED`, `INVALID` | — |
| `failed` | `CREATING_HOLD`, `HOLD_CREATED` | `FAILED` | `NOT_APPLICABLE` después del confirm |
| `cancelled` | `CONFIRMED` | `CANCELLED` | `NOT_APPLICABLE` en un intermedio (lo corrige la reconciliación) |
| `cancelledByReconciliation` | cualquier intermedio con hold, `CONFIRMED` | `CANCELLED` (y guarda la confirmación que informa la cátedra, si no tenía una) | — |

Para todos: un **final** da `FINAL_STATE` y `CONFIRMED` da `CONFIRMED_ONLY_CANCELS` (salvo las dos
cancelaciones). En `CREATING_HOLD`, los hechos que llegan por `reservationProcessId` quedan `NOT_APPLICABLE`: ese
proceso todavía no tiene ese ID. La matriz completa (11 estados × 11 hechos) está en `ReservationProcessMatrix`
(tests), y un IT guarda cada transición aplicada contra los `CHECK` de PostgreSQL.

## 4. Idempotencia

| Qué se repite | Por qué pasa | Qué hace turnos | Dónde |
| --- | --- | --- | --- |
| **El mismo evento de Kafka** (mismo `eventId`) | Entrega al menos una vez (§15.1, §18.1) | Barrera: `insert ... on conflict (event_id) do nothing` en `processed_event`, **en la misma transacción** que el cambio de estado. Si ya estaba, no se hace nada más. El offset se confirma después de persistir | CU-05, CU-07; [ADR 0006](adr/0006-kafka-en-turnos.md) |
| **Un evento distinto con el mismo efecto** (otro `eventId`, por ejemplo un resultado tardío) | Reintentos de la cátedra, orden | La máquina de estados: `NoEffect`, registrado como procesado sin efecto | CU-07 |
| **El mismo pedido de reserva** (doble toque, respuesta perdida) | La app reintenta | Si el usuario ya tiene un proceso en curso o confirmado para ese turno, `200` con ese proceso, sin crear nada; un segundo intermedio lo impide el índice único parcial (DPT-17) | CU-04 |
| **El teléfono** | El usuario reenvía después de un rechazo o de un `503` | `eventId` nuevo en cada envío (§15.5) y el último se guarda; si la cátedra recibe dos, gana el primer resultado | CU-06 |
| **La cancelación** | El usuario reintenta | Ya `CANCELLED`: `200` sin llamar a la cátedra. Si la primera no tuvo respuesta, la cátedra devuelve el estado de una reserva ya cancelada (§12) | CU-10 |
| **La convergencia** | "Mis reservas" y la reconciliación se repiten | Aplicar dos veces lo mismo da `NoEffect` la segunda | CU-09, CU-11 |

## 5. REST y Kafka en distinto orden

El estado puede llegar por REST y por Kafka en cualquier orden (enunciado §7). Turnos no supone ninguno:

- **El modelo acepta saltos hacia adelante** (por ejemplo `AppointmentConfirmed` antes de guardar
  `PHONE_SUBMITTED`) y rechaza los retrocesos con `NoEffect`.
- **Cada guardado relee** (`ReservationProcessUpdater` para los casos de uso por REST, la transacción del evento
  para Kafka). Si otro guardó entre la lectura y la escritura, el `UPDATE` no encuentra la versión y falla; el que
  perdió relee y vuelve a decidir (hasta 3 veces por REST; el listener reintenta con su `DefaultErrorHandler`).
- **La reconciliación recalcula la decisión sobre el proceso releído.** No alcanza con que el modelo rechace el
  hecho viejo: un rechazo puede haber traído un `expiresAt` nuevo mientras se esperaba a la cátedra.

Carreras probadas con dos escritores reales (la otra transacción por otra conexión, fuera del pool), en los dos
órdenes y 20 veces cada una: CU-06 contra un resultado (#12), CU-10 contra `AppointmentCancelled`, dos
cancelaciones simultáneas, CU-09 contra un evento y CU-11 contra un evento.

## 6. Recuperación

| Situación | Cómo se recupera | Cuánto tarda |
| --- | --- | --- |
| **Mensaje duplicado** | La barrera del `eventId` (sección 4) | En el momento |
| **Mensaje fuera de orden o tardío** (por ejemplo, `AppointmentConfirmed` después de `EXPIRED`) | La máquina de estados: un final no se reabre | En el momento |
| **Mensaje que no se pudo procesar** (la base caída 4 veces) | El offset se confirma igual (DPT-22) y el proceso queda en su estado: lo cierra CU-11 preguntándole a la cátedra | Pasado `expiresAt` + 1 min |
| **Mensaje perdido** (el resultado nunca llega) | "Mis reservas" (CU-09) converge si la cátedra lo tiene confirmado o cancelado; CU-11 lo cierra en cualquier caso | Al abrir "Mis reservas", o pasado `expiresAt` + 1 min |
| **Timeout al crear el hold** | `FAILED` con `HOLD_OUTCOME_UNKNOWN` en el momento: el `POST` nunca se repite (DPT-06); si se creó, vence solo | En el momento |
| **Timeout al confirmar** | `202` en `HOLD_CREATED` (DPT-07); CU-11 lista y, si la cátedra no lo tiene y no venció, reintenta el confirm | Entre 1 y 2 min |
| **Timeout al cancelar** | `503 CANCELLATION_OUTCOME_UNKNOWN`; el proceso sigue `CONFIRMED` y converge por `AppointmentCancelled` o por CU-09; reintentar es seguro | En el momento, o al llegar el evento |
| **Reinicio con un proceso a medias** | La primera ejecución de CU-11 corre al arrancar | Al arrancar (los recientes, cuando cumplen la antigüedad mínima) |
| **La cátedra caída** | Las consultas siguen con datos locales (CU-08, CU-09 con `catedraUpToDate: false`, CU-12); CU-11 reintenta en la próxima ejecución | Cuando vuelve |
| **El catálogo caído** | Solo fallan CU-03 y CU-04 (`503`, sin guardar nada): lo demás no lo necesita (DPT-11) | Cuando vuelve |

**"No está en la lista" es una prueba solo si la lista se leyó entera:** todas las páginas, sin fallas, con el
mismo total en cada una y sin ítems ilegibles. Si no, CU-11 espera a la próxima ejecución en vez de cerrar.

## 7. Timeouts, reintentos y qué pasa ante cada falla

| Llamada | Timeouts | Reintentos | Si falla |
| --- | --- | --- | --- |
| Catálogo: agenda (CU-03, CU-04) | 2 s conectar, 5 s respuesta | ninguno (DPT-13) | `503 CATALOG_UNAVAILABLE` |
| Cátedra: ocupaciones (CU-03) | 2 s, 5 s | ninguno | `503 CATEDRA_UNAVAILABLE` |
| Cátedra: crear el hold (CU-04) | 2 s, 5 s | **nunca** (DPT-06) | `FAILED`; `503` (`HOLD_OUTCOME_UNKNOWN` si conectó) |
| Cátedra: confirmar (CU-04) | 2 s, 5 s | ninguno con el usuario esperando | `202` en `HOLD_CREATED`; lo retoma CU-11 |
| Kafka: publicar el teléfono (CU-06) | comprobar el topic 5 s; envío 8 s | ninguno | `503 PHONE_SUBMISSION_UNCONFIRMED`; se puede reenviar |
| Cátedra: listar reservas (CU-09) | 2 s, 5 s por página; 10 s en total, hasta 5 páginas de 200 | ninguno | `200` con `catedraUpToDate: false` |
| Cátedra: cancelar (CU-10) | 2 s, 5 s | ninguno | `503` (`CANCELLATION_OUTCOME_UNKNOWN` si conectó) |
| Cátedra: listar (CU-11) | ídem CU-09 | hasta 3, con 1, 2 y 4 s | agotados, se corta la ejecución (4c) |
| Cátedra: reintentar el confirm (CU-11) | 2 s, 5 s | hasta 3, solo si no conectó, y nunca después de `expiresAt` | resultado desconocido: a la próxima ejecución, que vuelve a listar |
| Kafka: procesar un evento (CU-05, CU-07) | transacción de 10 s | hasta 3, con 1, 2 y 4 s (DPT-22) | offset confirmado; lo recupera CU-11 |

Ninguna espera es ilimitada: el peor caso de una ejecución de CU-11 es unos 155 s (2 min de plazo más un proceso
que agota la lista), y la del listener está en el [ADR 0006](adr/0006-kafka-en-turnos.md).

## 8. Supuestos y límites

- **Una sola instancia del servicio.** El "una ejecución a la vez" de CU-11 es un lock en la memoria del
  proceso. Con dos instancias, dos reconciliaciones podrían correr juntas; el bloqueo optimista evita que pisen
  datos, pero llamarían dos veces a la cátedra.
- **Tope de 50 procesos por ejecución, del cambio más viejo al más nuevo.** Un proceso que espera (por ejemplo
  `PHONE_PENDING` hasta 10 minutos) se vuelve a preguntar en cada ejecución. Con más de 50 esperando a la vez,
  los más nuevos no se revisarían hasta que los viejos se cierren. Con un intermedio por usuario (DPT-17) y el
  volumen del proyecto no pasa; no tiene código extra.
- **`PHONE_PENDING` más allá de 10 minutos** se cierra localmente como `EXPIRED` con una advertencia que lleva
  el `reservationProcessId`. Si después la cátedra lo confirmara, el proceso local quedaría cerrado. Medido: la
  cátedra pasa `PHONE_PENDING` a `FAILED` unos 5 s después de `expiresAt`, así que no debería pasar.
- **`processed_event` crece sin límite** (ver [`modelo-de-datos.md`](modelo-de-datos.md#5-límites-conocidos)).

## 9. Dónde está probado

| Qué | Tests |
| --- | --- |
| La máquina de estados entera | `ReservationProcessTest` con `ReservationProcessMatrix`; `ReservationProcessMatrixPersistenceIT` |
| La convergencia y la tabla de CU-11 | `ReservationConvergenceTest`, `ReconciliationPolicyTest` (todas las celdas y los bordes) |
| La barrera de `eventId` | `JpaProcessedEventRepositoryAdapterIT` (dos conexiones), `ReservationEventListenerIT` |
| Los flujos de punta a punta | `ReservationFlowsIT`, `ReservationStage3FlowsIT`, `ReconciliationAtStartupIT` |
| Las carreras | `ReservationFlowsIT`, `CancelReservationControllerIT`, `MyReservationsControllerIT`, `ReconciliationIT` |
| Cada flujo alternativo | `*UseCaseImplTest`, nombrados con su número (`cu09_4b_...`, `cu11_3a_...`) |
