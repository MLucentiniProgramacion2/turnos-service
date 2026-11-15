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

`KafkaPhoneSubmissionPublisher` publica `AdditionalInformationSubmitted` (CU-06) en el topic de
teléfono, con clave = `reservationProcessId` y el JSON exacto de §15.5 (todos los campos,
`eventType` y `schemaVersion` fijos, `occurredAt` en UTC sin fracciones). Variables nuevas, sin valor
por defecto: `CATEDRA_KAFKA_PHONE_TOPIC` y `CATEDRA_GROUP_ID`.

### Los plazos: el usuario está esperando

Por defecto, Kafka espera hasta 60 s en `max.block.ms` y 120 s en `delivery.timeout.ms`. Con el
usuario esperando la respuesta, se acotan a los valores de DPT-08:

| Propiedad | Valor | Qué acota |
| --- | --- | --- |
| `linger.ms` | 0 | no se espera a juntar un lote |
| `max.block.ms` | 2000 | cuánto bloquea `send` esperando la metadata del topic |
| `request.timeout.ms` | 3000 | cuánto se espera la respuesta de un pedido al broker |
| `delivery.timeout.ms` | 5000 | cuánto puede tardar la confirmación, con los reintentos de Kafka incluidos |
| plazo del futuro | 8000 | cuánto espera el caso de uso: `max.block.ms` + `delivery.timeout.ms` + 1 s |

Dos desigualdades:

- **La de Kafka:** `delivery.timeout.ms >= linger.ms + request.timeout.ms` (5000 ≥ 0 + 3000), o el
  productor no se construye. Se valida al arrancar (`@AssertTrue` en `CatedraKafkaProperties`) y un
  test construye el productor con la configuración real.
- **La del caso de uso:** el plazo del futuro es mayor que `max.block.ms` + `delivery.timeout.ms`. Así
  el productor siempre termina el futuro antes, con éxito o con su error, y un `503` nunca deja un
  envío "en vuelo" sin resolver que podría confirmarse después de haberle dicho al usuario que no.

`acks=all`: el broker confirma cuando el registro quedó escrito (CU-06 paso 6). La idempotencia del
productor (por defecto en Kafka 3 o más) evita duplicados si Kafka reintenta por su cuenta; no
reemplaza el `eventId` nuevo en cada envío de CU-06.

### Esperar la confirmación: `get` con plazo

En el Tema 13 el productor usa `whenComplete` y sigue. Acá eso no alcanza: el caso de uso guarda
`PHONE_SUBMITTED` solo si el broker confirmó, y tiene que responderle al usuario si se envió. Se espera
el `CompletableFuture` con `get(8 s)`. El `whenComplete` queda solo para el log del éxito.

### Comprobar el topic antes del primer envío

El productor no tiene `allow.auto.create.topics` (es del consumidor). **Medido con Testcontainers:** en
un broker que crea topics solo (el valor por defecto), publicar en un nombre mal escrito **confirma**
y crea el topic. El teléfono se perdería sin ningún error.

Por eso, antes del primer envío, `Admin.describeTopics` con un plazo de 2 s:

- **nunca al arrancar:** con Kafka o ZeroTier caídos, turnos levanta igual (enunciado §8);
- **se recuerda solo si salió bien:** si el broker estaba caído, el próximo envío vuelve a comprobar;
- **si no se pudo comprobar o el topic no existe, no se publica** (`503`, el proceso no cambia).

El plazo de la comprobación entra en el presupuesto de CU-06 solo hasta el primer éxito: 2 + 8 s en
el peor caso.

Contra el broker de la cátedra no se probó ningún nombre que no sea el real: el comportamiento con
`auto.create.topics.enable` se midió solo con Testcontainers.

### Lo medido (`KafkaPhoneSubmissionPublisherIT`, `apache/kafka-native:4.1.0`)

| Caso | Qué llega | Cuándo |
| --- | --- | --- |
| Envío normal | confirmación; clave y JSON de §15.5 | — |
| Sin comprobar, topic mal escrito, broker que crea topics | **confirmación**: el topic se crea | 1,6 s |
| Con la comprobación, topic que no existe | `PhoneSubmissionNotSentException`; el topic no se crea | — |
| Sin comprobar, topic que no existe, broker que no crea topics | `TimeoutException`: "Topic ... not present in metadata after 2000 ms" | 2,0 s |
| Broker caído (puerto cerrado) | `PhoneSubmissionNotSentException` (el `Admin` no consigue un nodo) | ~2,2 s, también la segunda vez |
| Broker pausado después de comprobar (no confirma) | `PhoneSubmissionUnconfirmedException` ← `TimeoutException`: "Expiring 1 record(s) ... 5002 ms has passed since batch creation" | 5,04 s, antes del plazo de 8 s |

### El teléfono fuera del log

- El `KafkaTemplate` usa un `ProducerListener` que no hace nada: el de por defecto
  (`LoggingProducerListener`) escribe en el log parte del valor del registro que falló.
- `KafkaProducerException` de Spring tiene un mensaje fijo ("Failed to send"): el adaptador loguea la
  falla de Kafka que lleva adentro, que nombra el topic y la partición, nunca el valor.
- `PhoneSubmission`, `SubmitPhoneCommand` y el DTO enmascaran el teléfono en `toString()`.
- `ReservationPhoneControllerIT` captura todo el log de un CU-06 completo y comprueba que los dígitos
  del teléfono no aparecen.

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
