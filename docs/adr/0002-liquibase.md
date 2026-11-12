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

**Pendiente:** el formato de los *changelogs* de negocio (XML como JHipster, o YAML/SQL) se
decide al escribir la primera migración.

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
