# CU-01: Registrarse

## Objetivo

Que una persona cree su cuenta de usuario final desde la app KMP y quede **activa en el momento**,
lista para iniciar sesión sin verificar el correo.

## Actores

- **Principal:** Usuario final, desde la app KMP.

## Disparador

La persona completa el formulario de registro de la app.

## Precondiciones

- Ninguna: el registro es un endpoint público (el resto de las operaciones exige JWT).

## Flujo principal

1. La app envía `login`, `password`, `firstName`, `lastName`, `email`, `langKey` y, opcionalmente,
   `imageUrl` al endpoint de registro de JHipster (`POST /api/register`).
2. Turnos valida los datos:
   - `login`: de **3** a 50 caracteres admitidos por el login de JHipster (DPT-20);
   - `password`: de 4 a 100 caracteres;
   - `firstName` y `lastName`: **obligatorios**, de 2 a 50 caracteres (DPT-02);
   - `email`: formato válido, de 5 a 254 caracteres;
   - `langKey`: de 2 a 10 caracteres;
   - `imageUrl`: opcional.
3. Turnos verifica que el `login` y el `email` no estén usados por otro usuario.
4. Turnos crea el usuario:
   - asigna el `id` interno, que es el que identifica al dueño de procesos y reservas (DPT-21) y
     el que se envía a la cátedra como `externalPatientId` (DPT-01);
   - guarda la contraseña con un hash criptográfico (nunca en texto plano);
   - lo deja **activado**;
   - le asigna solo la autoridad de usuario común (`ROLE_USER`);
   - completa los campos de auditoría.
5. Turnos responde `201 Created`, sin devolver la contraseña ni su hash.

## Flujos alternativos

- **1a. El cliente envía campos que administra el backend** (ID, estado de activación,
  autoridades, auditoría): se ignoran. Quien se registra no puede elegirlos (enunciado §3.2).
- **2a. Falta un campo obligatorio o tiene formato o largo inválido:** `400` en
  `application/problem+json` con `fieldErrors`. No se crea nada.
- **2b. Contraseña fuera de 4 a 100 caracteres:** `400` en `problem+json`.
- **3a. `login` ya usado:** `400` en `problem+json`.
- **3b. `email` ya usado:** `400` en `problem+json`.

Los nombres de los `code` propios de estos errores se fijan en el contrato REST de turnos para
KMP (DPT-18).

## Postcondiciones

- **De éxito:** existe un usuario activo con autoridad `ROLE_USER`, que puede iniciar sesión
  (CU-02) sin verificar el correo.
- **De fallo:** no se crea ningún usuario. La contraseña no aparece en la respuesta ni en los
  logs.

## Decisiones del proyecto

- **DPT-02 (nombre del paciente):** el paciente de una reserva es siempre el titular de la cuenta y
  sus nombres salen del perfil (ver CU-04). Para que la cátedra nunca los rechace en el confirm
  (2 a 100 caracteres, contrato §10), `firstName` y `lastName` son **obligatorios, de 2 a 50
  caracteres** (50 es el máximo de JHipster). La misma validación aplica al actualizar la cuenta
  (`POST /api/account`) y en la gestión de usuarios del admin de JHipster (crear y editar en
  `/api/admin/users`), para que el perfil no pueda quedar con nombres inválidos después del
  registro.
- **DPT-20 (largo mínimo del login):** el `login` va de 3 a 50 caracteres, como en el registro de
  la cátedra (contrato §5.1), en lugar del mínimo de 1 de JHipster. Al ser más estricto sigue
  siendo compatible con JHipster. Aplica también al crear y editar usuarios en `/api/admin/users`,
  donde el login sí se puede cambiar.
- **DPT-21 (dueño por `id`):** como el login puede cambiarlo un admin, los procesos y reservas
  guardan como dueño el `id` interno del usuario, nunca el login.

## Notas de implementación

- JHipster, por defecto, crea el usuario **desactivado** y envía un correo de activación. Eso se
  cambia para cumplir el enunciado §3.2 (activo inmediatamente, sin verificación por correo). No
  es una decisión del proyecto: lo exige la consigna.

## Respaldo en las consignas

- Enunciado §3.2: registro desde KMP con `login`, `password`, `firstName`, `lastName`, `email`,
  `imageUrl` y `langKey`; usuario activo inmediatamente; contraseña con mecanismo criptográfico;
  campos internos administrados por el backend; compatibilidad con el usuario de JHipster.
- Enunciado §5 punto 1: registrar y autenticar a cada usuario final.
- Enunciado §9: hashing de contraseñas, no exponerlas en respuestas ni logs, validar los datos en
  cada límite.
- Contrato §5.1: validaciones de referencia de la cátedra para `login`, `password`, `email` y
  `langKey`.
