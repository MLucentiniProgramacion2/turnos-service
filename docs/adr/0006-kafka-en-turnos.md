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
que no existen. **No se registran** un mensaje mal formado ni uno de un tipo desconocido: no tienen un
`eventId` confiable, o no hay nada que aplicar.

### Qué es "mal formado": solo lo que la transición necesita

Un mal formado se confirma sin aplicar, y sin la reconciliación (CU-11) el resultado se perdería. Por
eso es obligatorio **solo lo que hace falta para aplicar la transición sin romper un invariante**:

| Tipo | Obligatorio | Si falta otra cosa |
| --- | --- | --- |
| todos | `eventId`, `eventType`, `reservationProcessId` | — |
| `AdditionalInformationRequested` | `expiresAt` (el modelo lo guarda) | `holdId` ausente: se aplica por `reservationProcessId` (la transición no lo usa); `message` puede faltar |
| `AppointmentConfirmed` | `reservationId` positivo (el `CHECK` de `CONFIRMED` lo exige) | `occurredAt` ausente o ilegible: el reloj |
| `AdditionalInformationRejected` | — | `reason` ausente: `UNSPECIFIED`; `expiresAt` ausente o ilegible: se conserva el guardado |
| `AppointmentProcessExpired`, `AppointmentProcessInvalid` | — | `reason` ausente: `UNSPECIFIED` |
| `AppointmentCancelled` | — | `occurredAt` ausente: el reloj; `reason` de más de 500 caracteres: se recorta (es el máximo de la cátedra, §12) |

Cada reemplazo queda como advertencia en el log (`se aplica igual`). Es el mismo criterio que el
catálogo: no rechazar un dato real por algo que no se usa. Los textos de la cátedra (`reason` y
`message` de rechazos y cierres) van en columnas `text`, sin largo máximo, salvo el motivo de la
cancelación (`varchar(500)`).

### El nivel del log, por resultado

| Resultado | Nivel | Por qué |
| --- | --- | --- |
| Aplicado | INFO | lo normal |
| Duplicado (`eventId` ya registrado) | INFO | entrega al menos una vez: es normal, y es evidencia del §11 del enunciado |
| Sin efecto por `FINAL_STATE` o `CONFIRMED_ONLY_CANCELS` | INFO | un tardío o un duplicado con otro `eventId`: normal con Kafka |
| `AppointmentProcessInvalid` sobre `CONFIRMED` (CU-07 2.3b) | WARN | la cátedra y turnos no coinciden: lo revisa la reconciliación |
| Proceso que no existe (2.2a) | WARN | no debería pasar salvo con la base reseteada |
| Sin efecto por `NOT_APPLICABLE` o `NOT_FORWARD` (2.3c, 2.3e, CU-05 3.3c) | WARN | un hecho que no corresponde al estado |
| Mal formado o tipo desconocido | WARN | se descarta sin reintento |
| Un dato degradado | WARN | se aplica igual |
| Falla inesperada, cada intento | WARN | reintentos observables (§18.4) |
| Reintentos agotados | ERROR | el evento queda sin aplicar hasta CU-11 |

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

### Comprobar el topic: de fondo al arrancar, y antes del envío mientras tanto

El productor no tiene `allow.auto.create.topics` (es del consumidor). **Medido con Testcontainers:** en
un broker que crea topics solo (el valor por defecto), publicar en un nombre mal escrito **confirma**
y crea el topic. El teléfono se perdería sin ningún error. Por eso no se publica sin haber comprobado,
con `Admin.describeTopics`, que el topic existe. Lo hace `PhoneTopicCheck`, por dos caminos:

- **De fondo, al terminar el arranque** (`ApplicationReadyEvent`), con un plazo de **15 s**. Si falla,
  **se reintenta cada 30 s hasta que salga bien**, sin límite de tiempo. Nunca bloquea ni impide el
  arranque: el listener solo programa la tarea en un hilo propio y vuelve, así que con Kafka o
  ZeroTier caídos turnos levanta igual (enunciado §8). Deja un WARN por la primera falla y después
  DEBUG. Si el topic no existe, deja el mismo ERROR de configuración que el envío.
- **Antes de un envío, mientras la de fondo no salió bien**, con un plazo de **5 s**, el de lectura
  de DPT-08, porque el usuario está esperando. Si no se pudo comprobar o el topic no existe, no se
  publica (`503`, el proceso no cambia).

**Que no se pisen:** hay a lo sumo un `describeTopics` en vuelo, y cada camino lo espera **fuera del
lock** con su propio plazo. Un envío nunca espera los 15 s de la de fondo: espera el mismo pedido 5 s.
Un pedido que termina bien cuando nadie lo esperaba también cuenta: el próximo que pasa lo da por
comprobado sin pedir otro. Solo se recuerda el éxito. Con el éxito se cierra el `Admin`, que no se
vuelve a usar.

**Un hilo propio y no el `taskExecutor`** (el que usa el arranque del catálogo), por tres motivos:
- los reintentos necesitan una espera programada, que ese executor no tiene;
- el `taskScheduler` de `@EnableScheduling` no existe en los perfiles de test;
- en los tests el `taskExecutor` es sincrónico, así que no se podría probar que el arranque no espera.

El hilo es `daemon` y se apaga con el contexto.

#### Por qué estos plazos: la comprobación manual del #12

El primer teléfono después del arranque falló con `503 PHONE_SUBMISSION_UNCONFIRMED`. El `Admin` se
creaba con ese envío, y en los 2 s que tenía la comprobación no llegó a conectarse por ZeroTier
(entre 200 y 290 ms por viaje): `Timed out waiting to send the call. Call: listNodes`. Tres minutos
después, el reintento publicó. `PhoneTopicCheckManualCheck` lo midió contra el broker real, en dos
corridas, con un `Admin` nuevo por intento y un plazo de 15 s:

| Medición | En frío | Ya conectado |
| --- | --- | --- |
| `Admin.describeTopics`, corrida 1 (3 de 5 intentos: el timeout global de JUnit cortó el cuarto) | 5080, 2931 y 2594 ms | 534, 506 y 1135 ms |
| `Admin.describeTopics`, corrida 2 | **sin respuesta en 15 s** (`listNodes`); 2565, 3991, 1933 y 1973 ms | 1256, 599, 1761 y 506 ms |
| `KafkaProducer.partitionsFor`, corrida 2, después del `Admin` (sin publicar) | 772 a 928 ms, los 5 | — |

- **La de fondo, 15 s:** es unas 3 veces el peor caso que respondió (5,1 s). Nadie la espera, así que
  puede ser larga. El intento que no respondió ni en 15 s muestra por qué hacen falta los reintentos:
  el primer contacto puede no llegar (ZeroTier armando el camino) y el siguiente anduvo.
- **La del envío, 5 s:** con 2 s fallaba en frío la mayoría de las veces (5 de los 7 intentos que respondieron
  tardaron más), y ya conectado llegó a 1,76 s. 5 s es el plazo de lectura de DPT-08. En frío puede
  no alcanzar (5,1 s), pero normalmente la de fondo ya terminó antes del primer teléfono.
- **El productor no se calienta:** en frío trajo los metadatos del topic en menos de 1 s, con margen
  sobre `max.block.ms` (2 s). Se midió **después** de la comprobación del `Admin`, con ZeroTier ya
  despierto, que es el mismo orden que tiene en producción gracias a la comprobación de fondo.
- **Por qué el `Admin` tarda más que el productor:** en parte porque hace más pedidos. El error de la
  comprobación manual nombra `listNodes`, que el `Admin` pide antes de la descripción; el productor
  pide solo los metadatos del topic. Eso suma viajes de 250 ms, pero no explica toda la diferencia
  (de 2 a 5 s contra menos de 1 s). Además, el productor se midió con el camino de ZeroTier ya
  despierto. Cuánto pesa cada cosa: **no medido**.

#### El presupuesto de CU-06 con el usuario esperando

| Tramo | Plazo |
| --- | --- |
| Comprobación del topic (solo mientras la de fondo no salió bien) | 5 s |
| Plazo del futuro del envío (`max.block.ms` 2 s + `delivery.timeout.ms` 5 s + 1 s) | 8 s |
| **Peor caso antes del primer éxito** | **13 s** (antes, 2 + 8 = 10 s) |
| **Peor caso después del primer éxito** (lo normal) | **8 s** |

A eso se suman las lecturas y los guardados del proceso en la base, que son locales. Las dos
desigualdades de arriba no cambian: la comprobación termina antes del envío, y el futuro sigue
esperando más que `max.block.ms` + `delivery.timeout.ms`.

Contra el broker de la cátedra no se probó ningún nombre que no sea el real: el comportamiento con
`auto.create.topics.enable` se midió solo con Testcontainers. La comprobación manual solo describe el
topic real y pide `partitionsFor` después de confirmar que existe; no publica.

### Lo medido (`KafkaPhoneSubmissionPublisherIT`, `apache/kafka-native:4.1.0`)

| Caso | Qué llega | Cuándo |
| --- | --- | --- |
| Envío normal | confirmación; clave y JSON de §15.5 | — |
| Sin comprobar, topic mal escrito, broker que crea topics | **confirmación**: el topic se crea | 1,6 s |
| Con la comprobación, topic que no existe | `PhoneSubmissionNotSentException`; el topic no se crea | — |
| Sin comprobar, topic que no existe, broker que no crea topics | `TimeoutException`: "Topic ... not present in metadata after 2000 ms" | 2,0 s |
| Broker caído (puerto cerrado) | `PhoneSubmissionNotSentException` (el `Admin` no consigue un nodo) | el plazo del envío, 5 s (el test lo acota entre 4,9 y 7 s), también la segunda vez; con 2 s eran ~2,2 s |
| Comprobación de fondo terminada | el primer envío no vuelve a describir el topic (1 solo pedido) | — |
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
- **Comprobar el topic durante el arranque, esperándola:** con Kafka o ZeroTier caídos, turnos no
  levantaría o tardaría 15 s en estar listo (§8).
- **Solo subir el plazo del envío, sin comprobación de fondo:** el primer teléfono pagaría la conexión
  en frío (de 2 a 5 s, y a veces más de 15) con el usuario esperando.
- **Un intento de fondo sin reintentos:** un arranque con la red caída volvería al problema de la
  comprobación manual del #12.
- **Calentar también el productor:** medido, en frío tarda menos de 1 s, con margen sobre
  `max.block.ms`.
