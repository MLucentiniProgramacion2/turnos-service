# ADR 0006: Kafka en turnos: consumidor del topic de acciones, idempotencia y productor del teléfono

- **Estado:** aceptada
- **Fecha:** 2026-11-15
- **Relacionada con:** [CU-05](../use-cases/CU-05-recibir-pedido-de-informacion-adicional.md),
  [CU-06](../use-cases/CU-06-informar-telefono.md),
  [CU-07](../use-cases/CU-07-procesar-resultado-del-proceso.md),
  [DPT-15 y DPT-22](../use-cases/README.md#decisiones-del-proyecto), ADR 0005 del catálogo

## Contexto

Turnos usa Kafka en las dos direcciones:

- **consume** el topic de acciones (`kafkaAppointmentActionsTopic`): el pedido de teléfono (CU-05) y
  los cinco resultados del proceso (CU-07);
- **publica** en el topic de teléfono (`kafkaAppointmentPhoneTopic`) el evento
  `AdditionalInformationSubmitted` (CU-06).

El catálogo ya resolvió el consumidor en su ADR 0005, medido contra un broker real. Este ADR lo adopta y
anota lo que cambia en turnos.

Lo que exigen las consignas:

- **Contrato §15.1 y §18.1:** entrega al menos una vez, `eventId` como clave de idempotencia, campos
  desconocidos ignorados, orden por clave (`reservationProcessId`).
- **Enunciado §8 y contrato §18.4:** reintentos limitados y observables; un duplicado no repite efectos.
- **DPT-15:** el `eventId` se registra en la misma transacción que el cambio de estado, también los
  "sin efecto"; el offset se confirma después.
- **DPT-22:** si falla la transacción, hasta 3 reintentos con 1, 2 y 4 s; después, offset confirmado y
  recuperación por CU-11. Un mensaje mal formado no se reintenta.

Medido el 14/11 con `kcat`, sin consumer group: los dos topics existen, tienen una partición y estaban
vacíos. Kafka de la cátedra: PLAINTEXT, sin credenciales, un broker.

## Decisión: el consumidor

Igual que el ADR 0005 del catálogo, con estos valores:

| Propiedad | Variable | Valor |
| --- | --- | --- |
| `catedra.kafka.bootstrap-servers` | `CATEDRA_KAFKA_BOOTSTRAP_SERVERS` | sin valor por defecto: sin ella no arranca |
| `catedra.kafka.actions-topic` | `CATEDRA_KAFKA_ACTIONS_TOPIC` | sin valor por defecto |
| `catedra.kafka.consumer-group` | `CATEDRA_KAFKA_CONSUMER_GROUP` | sin valor por defecto; el real es `alumnos-<groupId>-turnos` |
| `catedra.kafka.max-poll-interval` | (no) | `2m` |
| `catedra.kafka.auth-retry-interval` | (no) | `1m` |
| `catedra.kafka.retry-delays` | (no) | `1s, 2s, 4s` (DPT-22) |
| `catedra.kafka.listener-enabled` | (no) | `true`; solo `src/test` lo apaga |
| `reservation.event-transaction-timeout` | (no) | `10s` |

Lo que se mantiene del catálogo, con el mismo motivo:

- **Configuración en Java**, no `spring.kafka.*`: el `application.yml` de `src/test` tapa al principal.
  `ReservationEventListenerIT` define `spring.kafka.*` con valores trampa.
- **`String` y parseo propio** (`ReservationEventMessageMapper`): un mensaje roto llega al listener y
  no traba la partición. `ACCEPT_FLOAT_AS_INT` apagado.
- **`AckMode.RECORD`**, `enable.auto.commit=false`, `allow.auto.create.topics=false`, sin `NewTopic`,
  `earliest`, `authExceptionRetryInterval` y `setAlwaysStartAfterRefresh(false)`.
- **`DefaultErrorHandler` con las esperas de DPT-22**, un recoverer que nunca lanza y un retry listener
  que loguea la falla real (desenvolviendo solo `ListenerExecutionFailedException`).
- **Un solo hilo** (`concurrency=1`): el topic tiene una partición.

### Lo que cambia respecto del catálogo

**Un consumer group propio: `alumnos-<groupId>-turnos`.** El catálogo midió que un rebalanceo de un
grupo compartido dejó 29 s sin entregas a otro topic del mismo grupo. Turnos no puede atrasarse así:
sus holds vencen en minutos.

**`max.poll.interval.ms` = 2 min.** Acá no hay una exclusión de 12 minutos como en el catálogo. Hay
que separar dos cuentas:

| Cuenta | Tramos | Total |
| --- | --- | --- |
| **Por intento** (lo que cuenta para `max.poll.interval.ms`) | esperar una conexión de Hikari (30 s) + la transacción con su plazo (10 s) + la espera más larga antes del intento siguiente (4 s) | **44 s** |
| **Por mensaje completo** | 4 intentos de 40 s + 1 + 2 + 4 s de esperas | **167 s** |

Cada reintento vuelve por otro `poll` y arranca un plazo nuevo, pero la espera previa corre dentro del
plazo de esa entrega (medido en el catálogo). Por eso lo que tiene que entrar en
`max.poll.interval.ms` es un intento: con 2 min sobran 76 s. `max.poll.records=1` hace que el plazo
sea por mensaje y no por lote. `ReservationEventWorstCaseTest` lee los valores de los YAML de
`src/main` (Hikari no está configurado: vale su valor por defecto de 30 s) y recalcula.

**Supuesto que hace cerrar la cuenta:** la base responde o corta. El plazo de la transacción acota las
sentencias, también la espera de un bloqueo (**medido:** con un plazo de 1 s, una barrera bloqueada
por otra transacción cortó a los 1,3 s con `QueryTimeoutException`). No acota un cuelgue de red sin
respuesta ni el `commit`, porque el driver de PostgreSQL no tiene `socketTimeout` configurado. Si eso
pasara, el consumidor saldría del grupo, el commit fallaría y el mensaje se reentregaría: la barrera
cubre el duplicado.

**El plazo de la transacción va en un envoltorio** (`EventTransactions`), no en un segundo bean
`TransactionTemplate`: la autoconfiguración de Spring Boot crea el suyo solo si no hay ninguno, y los
casos de uso HTTP terminarían usando el de los eventos, con plazo. Lo fija `ReservationPropertiesTest`.

**Un listener para los seis tipos.** `ReservationEventListener` lee lo común (`eventId`, `eventType`,
`reservationProcessId`), valida lo que necesita cada tipo y despacha por `eventType`. La secuencia de
base (barrera, búsqueda, transición, guardado) está una sola vez, en `ReservationEventProcessor`; cada
caso de uso solo le pasa la transición del modelo.

## Decisión: la idempotencia

En una transacción, en este orden (`ReservationEventProcessor`):

1. **Barrera: insertar y mirar las filas.** `insert ... on conflict(eventId) do nothing` en HQL, como
   el catálogo. Devuelve 0 si el `eventId` ya estaba: duplicado, no se hace nada más.
2. Buscar el proceso por `reservationProcessId`. Si no existe, el evento queda registrado sin efecto.
3. Aplicar la transición del modelo. Si devuelve "sin efecto", el evento queda registrado igual.
4. Guardar el proceso si cambió (con `@Version`).

Si algo falla, se deshace todo (también el registro del `eventId`) y la excepción va al
`DefaultErrorHandler`, que reintenta.

**Por qué "insertar y mirar" y no "consultar y después insertar":** con dos entregas simultáneas del
mismo evento, las dos consultas darían "no está" y las dos aplicarían el efecto; después una de las dos
chocaría con la clave primaria y deshacería su transacción entera. Con el `insert`, la segunda entrega
**espera** el bloqueo de la primera sobre esa clave: si la primera confirma, devuelve 0; si se deshace,
inserta. Medido con dos conexiones en `JpaProcessedEventRepositoryAdapterIT`. Con un solo consumidor
esto no debería pasar, pero el costo de equivocarse es aplicar un evento dos veces.

**Concurrencia con los pedidos HTTP.** El listener y CU-04 (o CU-06) pueden escribir el mismo proceso
a la vez. El que pierde por `@Version` relee y vuelve a aplicar: CU-04 y CU-06 con su relectura
(`ReservationProcessUpdater`, hasta 3 intentos), el listener por el `DefaultErrorHandler`. **Medido**
en `ReservationEventListenerIT`: el intento 1 falló con `ObjectOptimisticLockingFailureException`, el
2 leyó el estado nuevo y lo avanzó.

**Qué se registra:** todo evento que llegó a la transacción, con o sin efecto, también los de procesos
que no existen. **No se registran** un mensaje mal formado, un tipo desconocido ni (hasta la parte 5
del issue #12) un resultado de CU-07: no tienen un `eventId` confiable o todavía no se procesan.

## Decisión: el productor

Se completa con CU-06 (parte 4 del issue #12).

## Consecuencias

- Sin las tres variables, la aplicación no arranca. Con el broker caído o sin ZeroTier, arranca igual
  (enunciado §8): el listener reintenta conectarse y las operaciones que no dependen de Kafka siguen.
- Un evento que falla 4 veces queda sin aplicar hasta que exista CU-11. Mientras tanto, el usuario
  puede quedar con un proceso intermedio (ver la explicación del issue #12 para destrabarlo).
- Los logs de Kafka pueden nombrar el broker (`Connection to node...`): `logback-spring.xml` deja
  `org.apache` en WARN. Es un host, no una credencial.

## Alternativas descartadas

- **`spring.kafka.*` en el YAML (Tema 13):** los tests no la ejercitarían.
- **El grupo sugerido sin sufijo, compartido con el catálogo:** ver arriba.
- **Consultar y después insertar el `eventId`:** ver "la idempotencia".
- **Un listener por tipo de evento:** seis copias de la barrera y la búsqueda, y el mismo topic leído
  seis veces.
