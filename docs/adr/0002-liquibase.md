# ADR 0002: Liquibase para las migraciones de base de datos

- **Estado:** aceptada
- **Fecha:** 2026-10-21
- **Relacionada con:** [ADR 0001: PostgreSQL](0001-postgresql.md)

## Contexto

El esquema de la base del servicio (ver ADR 0001) va a cambiar a lo largo del proyecto:
usuarios, procesos de reserva, eventos procesados, índices como el de DPT-17, etc. Esos cambios
tienen que ser reproducibles en cualquier entorno, quedar versionados en Git y aplicarse
automáticamente.

Requisitos del enunciado (`PROJECT_STATEMENT-v1.md`):

- **§3:** cada servicio es propietario exclusivo de sus datos y **administra sus propias
  migraciones**, independientes de las del servicio de catálogo. No se integra por base
  compartida.
- **§3.1:** el uso de JHipster es opcional y recomendado. El profesor aprobó (10/10) usarlo para
  la base del servicio (usuarios, login, JWT, seguridad y configuración), con las features de
  negocio escritas a mano en arquitectura hexagonal.
- **§12:** el modelo de datos de cada servicio tiene que estar documentado y coincidir con la
  solución entregada.

Además, generar el esquema con Hibernate (`ddl-auto=update`) no deja historial ni sirve para la
base real. Solo se acepta en tests.

## Decisión

Usar **Liquibase** para versionar y aplicar las migraciones del servicio. Los *changelogs*
viven en este repositorio y se aplican al arrancar la aplicación. Hibernate solo **valida** el
esquema (`ddl-auto=validate`) y nunca lo modifica.

Liquibase es la herramienta que JHipster genera y configura para las tablas de usuarios y
seguridad, así que el servicio usa **una sola** herramienta de migraciones:

- Los *changelogs* que genera JHipster quedan como están.
- Las tablas de negocio (procesos de reserva, eventos procesados) van en **changelogs propios,
  separados** de los de JHipster e incluidos desde el *changelog* maestro.

Es la misma decisión que tomó el servicio de catálogo (su ADR 0002), pero cada servicio tiene sus
propios *changelogs* y su propia tabla de control: ninguno aplica migraciones sobre la base del
otro.

**Formato de los *changelogs* de negocio (resuelto en el issue #10):** XML, como los de JHipster y
los del catálogo, uno por tabla, en `config/liquibase/changelog/<feature>/` (por ejemplo,
`reservation/001-reservation-process.xml`) e incluidos uno por uno desde `master.xml`, porque el
orden importa. Lo que el XML de Liquibase no tiene en la edición gratuita (`CHECK` e índices con
condición) va en *changesets* `sql`, con su *rollback* explícito.

## Alternativas consideradas

| Alternativa | Motivo para descartarla |
| --- | --- |
| **Flyway** | Más simple: migraciones en SQL plano numerado (`V1__...sql`). Pero no es lo que genera JHipster: habría que reemplazar la configuración generada o convivir con dos herramientas sobre la misma base. Además, el *rollback* no está en la edición gratuita. |
| **`ddl-auto=update` de Hibernate** | No deja historial, no es reproducible y puede alterar o perder datos. Tampoco sabe crear un índice único parcial como el de DPT-17. Solo se admite en tests. |
| **Scripts SQL manuales** | Sin registro de qué se aplicó en cada base; propenso a errores y difícil de reproducir. |

## Consecuencias

- **Positivas:**
  - Cada cambio de esquema es un *changeset* identificado; Liquibase registra cuáles se
    aplicaron en las tablas `DATABASECHANGELOG` y `DATABASECHANGELOGLOCK`.
  - Una sola herramienta para las tablas de JHipster y las de negocio, sin tocar lo generado.
  - Combinado con PostgreSQL (DDL transaccional), una migración que falla no deja el esquema
    a medias.
  - Los *changelogs* son, a la vez, documentación versionada del modelo de datos (§12).
- **Negativas:**
  - Agrega una capa de abstracción respecto de escribir SQL directo, y su formato hay que
    aprenderlo.
  - El índice único parcial de DPT-17 depende de PostgreSQL y el `createIndex` de Liquibase no
    admite condición: hay que escribirlo en un *changeset* `sql`, que no es portable.
  - Un *changeset* ya aplicado no se edita: cualquier corrección va en uno nuevo (Liquibase
    valida *checksums* y falla si cambia uno ya aplicado).

## Nota (11/11): validación del esquema y Liquibase sincrónico (issue #10)

La configuración que generó JHipster dejaba dos huecos en la decisión "Hibernate solo valida", los
mismos que encontró el catálogo en su #15:

1. **Los tests no validaban el esquema.** `application-testdev.yml` y `application-testprod.yml`
   tenían `ddl-auto: none` y `hibernate.hbm2ddl.auto: none` (con un TODO de JHipster). El `validate`
   del `application.yml` principal no llega a los tests, porque el `src/test/resources/config/application.yml`
   lo tapa en el classpath. Un test podía pasar con una entidad que no coincidía con su tabla.
2. **En el perfil `dev`, Liquibase corría en paralelo con el arranque.** Con
   `application.liquibase.async-start: true` (valor por defecto de JHipster), `AsyncSpringLiquibase`
   ejecuta la migración en otro hilo si está activo `dev`. El `EntityManagerFactory` depende del bean
   de Liquibase, pero solo espera a que se inicialice, y en modo asincrónico eso vuelve enseguida. El
   aviso `Starting Liquibase asynchronously` apareció en el log de turnos el 8/11.

**Se decidió:**

- `ddl-auto: validate` también en los perfiles de test, y se borra `hibernate.hbm2ddl.auto`, que en
  Spring Boot tiene prioridad sobre `ddl-auto`.
- `application.liquibase.async-start: false` en el `application.yml` principal y en el de test:
  Liquibase termina antes de que Hibernate valide, en todos los perfiles.

**Por qué:** la validación solo sirve si corre en todos lados y después de las migraciones. Si no,
una diferencia entre entidad y tabla aparece recién en ejecución. El costo es que el perfil `dev`
arranca un poco más lento, porque espera a Liquibase.

**Comprobación:**

- Con una columna inexistente agregada a una entidad de test, 81 de los 90 tests de integración
  fallan por la validación (`missing column`). Los 9 restantes levantan un contexto sin JPA.
- `dev` contra un PostgreSQL temporal y vacío, con un *changeset* que hace `pg_sleep(5)` antes de
  crear la tabla de una entidad de prueba. Antes del cambio, Liquibase arrancó en otro hilo y
  Hibernate validó 1,5 s después, sin la tabla (`missing table`). Con el cambio, arranca.
