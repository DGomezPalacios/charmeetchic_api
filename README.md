# charmeetchic-api

API REST para el **control de inventario** de la tienda de accesorios y joyas **Charme et Chic**, diseñada para
crecer hacia un ecommerce (carrito, checkout, pagos).

- **Stack:** Java 17+ · Spring Boot 3.2 · Spring Security · Spring Data JPA · H2 (desarrollo) · JWT · Maven
- **Autenticación:** **Firebase Authentication**. La API valida los *ID tokens* de Firebase (firma RS256 con las claves
  públicas de Google, emisor, audiencia, `sub` y expiración)
- **Autorización:** roles `ADMIN` y `USER` (custom claims de Firebase)
- **URL base:** `http://localhost:8080/api`

---

## Cómo ejecutar

Requisitos: **JDK 17 o superior** y **Maven 3.9+**.

```bash
mvn spring-boot:run
```

| Qué | URL |
|---|---|
| API | http://localhost:8080/api |
| Health check | http://localhost:8080/api/health |
| Catálogo (público) | http://localhost:8080/api/products |
| Consola H2 | http://localhost:8080/api/h2-console |

Consola H2: JDBC URL `jdbc:h2:mem:testdb`, usuario `sa`, contraseña vacía.

Al arrancar se cargan 5 categorías y 15 productos de ejemplo (`src/main/resources/data.sql`, con
instrucciones para agregar más). La base de datos es **en memoria**: se borra al detener la aplicación.

Otros comandos:

```bash
mvn test                 # tests unitarios + integración
mvn package              # genera target/charmeetchic-api-0.0.1-SNAPSHOT.jar
java -jar target/charmeetchic-api-0.0.1-SNAPSHOT.jar
```

> **Antes de usar los endpoints protegidos** configura tu Project ID de Firebase (ver
> [Configurar Firebase](#configurar-firebase-authentication)). Sin él la aplicación arranca igual con el valor por
> defecto `charmeetchic`, que solo aceptará tokens de un proyecto con ese ID. Los endpoints públicos (catálogo,
> categorías, health) funcionan siempre. Para probar sin un proyecto de Firebase, ver
> [Probar sin Firebase](#probar-sin-firebase-modo-local).

---

## Endpoints principales

Todas las rutas cuelgan de `/api` (context-path). Errores y paginación: ver secciones más abajo.

### Productos

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| GET | `/api/products?page=0&size=10&sort=name,desc` | Público | Catálogo paginado (solo productos activos) |
| GET | `/api/products/search?q=perla&category=1` | Público | Búsqueda por texto (nombre/descripción) y/o categoría |
| GET | `/api/products/{id}` | Público | Detalle de un producto activo |
| GET | `/api/products/low-stock` | ADMIN | Productos activos con stock bajo el umbral (por defecto 5) |
| POST | `/api/products` | ADMIN | Crear producto → `201` + cabecera `Location` |
| PUT | `/api/products/{id}` | ADMIN | Actualizar producto (ver *control de concurrencia*) |
| DELETE | `/api/products/{id}` | ADMIN | Borrado **lógico** (`active=false`) → `204` |

### Categorías

| Método | Ruta | Acceso |
|---|---|---|
| GET | `/api/categories`, `/api/categories/{id}` | Público |
| POST | `/api/categories` | ADMIN |
| PUT | `/api/categories/{id}` | ADMIN |
| DELETE | `/api/categories/{id}` | ADMIN (`409` si tiene productos) |

### Órdenes

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| POST | `/api/orders` | Autenticado | Crea una orden `PENDING` para el usuario del token y **descuenta stock** |
| GET | `/api/orders` | Autenticado | Mis órdenes (más reciente primero) |
| GET | `/api/orders/{id}` | Autenticado | Una orden mía (`403` si es de otro; ADMIN puede ver cualquiera) |
| PUT | `/api/orders/{id}/status` | ADMIN | Avanza el estado: `PENDING → PROCESSING → SENT → DELIVERED` |
| DELETE | `/api/orders/{id}` | Autenticado | Cancela una orden mía **solo si está `PENDING`**; devuelve el stock |

### Reportes (todos ADMIN)

Los reportes con periodo aceptan `from` y `to` (`yyyy-MM-dd`, ambos inclusive). Sin ellos: últimos 30 días.

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/api/reports/sales?from=&to=` | Nº de órdenes, subtotal, IVA, total y ticket promedio |
| GET | `/api/reports/sales-by-category?from=&to=` | Unidades e ingresos por categoría |
| GET | `/api/reports/inventory-value` | Valor del inventario a costo y a precio de venta, utilidad potencial |
| GET | `/api/reports/top-products?from=&to=&limit=10` | Productos más vendidos (`limit` 1–50) |

### Otros

| Método | Ruta | Acceso | Descripción |
|---|---|---|---|
| GET | `/api/health` | Público | `{"status":"UP", ...}` |
| GET | `/api/profile` | Autenticado | Datos y roles del usuario del token |

### Ejemplos

`$ID_TOKEN` es el ID token de Firebase del usuario (ver [Conectar con el frontend](#conectar-con-el-frontend-angular)).

```bash
# Catálogo público
curl "http://localhost:8080/api/products?page=0&size=5&sort=price,desc"

# Crear un producto (ADMIN)
curl -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer $ID_TOKEN_ADMIN" -H "Content-Type: application/json" \
  -d '{
        "name": "Collar Corazón Oro",
        "description": "Baño de oro 18k",
        "price": 22990.00,
        "cost": 9500.00,
        "sku": "COL-COR-010",
        "stock": 12,
        "categoryId": 1,
        "images": ["/images/products/collar-corazon-oro.jpg"]
      }'

# Comprar — el precio lo pone el servidor, el usuario sale del token
curl -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer $ID_TOKEN" -H "Content-Type: application/json" \
  -d '{
        "shippingAddress": "Av. Providencia 1234, Santiago",
        "items": [ { "productId": 1, "quantity": 2 }, { "productId": 4, "quantity": 1 } ]
      }'

# Avanzar una orden (ADMIN)
curl -X PUT http://localhost:8080/api/orders/1/status \
  -H "Authorization: Bearer $ID_TOKEN_ADMIN" -H "Content-Type: application/json" \
  -d '{ "status": "PROCESSING" }'
```

### Reglas de negocio importantes

- **Precios:** son **netos**. Al crear la orden: `subtotal = Σ precio × cantidad`, `tax = subtotal × tasa` (19 % por
  defecto, configurable), `total = subtotal + tax`. El precio de cada línea se **congela** en la orden.
- **Stock:** se descuenta al crear la orden y se devuelve al cancelarla. Comprar más que el stock → `409`.
- **Concurrencia:** `Product` tiene `@Version` (bloqueo optimista). Si dos compras compiten por la última unidad, una
  recibe `409`. En `PUT /products/{id}` puedes enviar el campo `version` que leíste: si otro admin modificó el
  producto entretanto, recibes `409` en vez de pisar su cambio.
- **Borrado de productos:** lógico. Un producto inactivo desaparece del catálogo público (404), pero las órdenes
  históricas lo siguen referenciando. Para reactivarlo: `PUT` con `"active": true`.
- **SKU:** único; se normaliza a MAYÚSCULAS. **Categorías:** nombre único (sin distinguir mayúsculas).
- **Estados de orden:** solo avanzan de a un paso; no hay retroceso ni saltos.

### Formato de errores

Todos los errores (incluidos 401/403) usan el mismo cuerpo:

```json
{
  "timestamp": "2026-09-20T21:10:31.512Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Datos de entrada inválidos",
  "path": "/api/products",
  "fieldErrors": { "price": "El precio debe ser mayor que 0", "sku": "El SKU es obligatorio" }
}
```

| Código | Cuándo |
|---|---|
| 400 | Validación (`fieldErrors`), JSON mal formado, parámetro inválido, transición de estado inválida |
| 401 | Token ausente/inválido/expirado (`WWW-Authenticate: Bearer`). Ver mensajes abajo |
| 403 | Rol insuficiente u orden de otro usuario |
| 404 | Recurso inexistente |
| 409 | SKU/nombre duplicado, stock insuficiente, conflicto de versión, orden no cancelable, categoría con productos |
| 500 | Error inesperado (el detalle se registra en el log, no se expone) |

Mensajes de `401` específicos de Firebase:

| `message` | Causa |
|---|---|
| `Autenticación requerida: envía el ID token de Firebase como Bearer` | Falta la cabecera `Authorization` en un endpoint protegido |
| `El token de Firebase ha expirado; obtén uno nuevo con getIdToken()` | Los ID tokens duran 1 hora |
| `El token no pertenece a este proyecto de Firebase (issuer inválido)` / `(audience inválida)` | Token de otro proyecto, o `FIREBASE_PROJECT_ID` mal configurado |
| `Token de Firebase inválido` | Firma incorrecta, token mal formado o sin firmar |
| `Algoritmo de firma no permitido: ...` | El token no está firmado con RS256 |
| `Clave de firma desconocida para este token` | `kid` que Google no publica |
| `El token no contiene el UID de Firebase (sub)` | Falta `sub` |

### Paginación

`GET /api/products?page=0&size=10&sort=name,desc` devuelve un `Page<ProductDTO>` de Spring Data
(`content`, `totalElements`, `totalPages`, `number`, `size`...). Tamaño máximo de página: 100. Un campo de
ordenamiento inexistente responde `400`.

---

## Seguridad y roles

| Rol | Puede |
|---|---|
| *(anónimo)* | Health, catálogo y categorías (solo lectura) |
| `USER` | Lo anterior + crear/consultar/cancelar **sus** órdenes + `/profile` |
| `ADMIN` | Todo lo de `USER` + escritura de catálogo y categorías, estados de orden, stock bajo y reportes |

- Todo usuario autenticado en Firebase es al menos `USER`; un `ADMIN` también tiene `USER`.
- Sesiones **stateless**, CSRF desactivado (no hay cookies), CORS solo para los orígenes configurados.
- Un token inválido responde `401` **incluso en endpoints públicos** (indica un cliente mal configurado). Como
  `getIdToken()` renueva el token solo, el frontend no debería toparse con esto.
- La primera vez que llega un token válido se registra el usuario en la tabla `users` (`GET /api/profile` lo devuelve).
  `userId` es el **UID de Firebase** (claim `sub`).

### Qué valida el backend en cada token

La verificación sigue el checklist oficial de Firebase para ID tokens y se hace en `JwtService`:

| Comprobación | Valor esperado |
|---|---|
| Firma | **RS256**, con la clave pública de Google cuyo `kid` coincide (JWKS de `securetoken@system.gserviceaccount.com`, cacheado) |
| `iss` | `https://securetoken.google.com/<PROJECT_ID>` |
| `aud` | `<PROJECT_ID>` |
| `sub` | UID de Firebase, no vacío |
| `exp` | presente y en el futuro |
| `iat`, `auth_time` | no en el futuro |

> **La firma se verifica siempre.** Decodificar el token sin comprobar la firma permitiría que cualquiera fabrique un
> token con el `iss`/`aud` correctos y se haga pasar por ADMIN. No hace falta el Admin SDK ni credenciales: las claves de
> firma de Google son públicas.

### Configurar Firebase Authentication

1. **Crear el proyecto:** [Firebase Console](https://console.firebase.google.com) → *Add project*.
2. **Project ID:** *Project settings* → *General* → **Project ID** (p. ej. `charmeetchic-a1b2c`; no es el *nombre* ni el
   *Project number*). Configúralo con la variable de entorno `FIREBASE_PROJECT_ID` o en `application.yml`
   (`firebase.project-id`).
3. **Habilitar el login:** *Authentication* → *Sign-in method* → activa Email/Password, Google, etc.
4. **Registrar la app web:** *Project settings* → *Your apps* → *Web* → copia el `firebaseConfig` para Angular.
5. **Asignar roles (ver siguiente sección).**

### Roles con custom claims

Los roles viajan **dentro del ID token** como *custom claims*, que solo puede escribir alguien con credenciales de
administrador (el cliente no puede modificarlos). El backend acepta cualquiera de estas formas:

```json
{ "roles": ["ADMIN"] }      // recomendada
{ "role": "ADMIN" }
{ "admin": true }
{ "ADMIN": true }           // el que asigna addAdmin.js (solo cuenta el booleano true)
```

Sin claims (o con valores desconocidos) el usuario es `USER`. Para convertir a alguien en ADMIN usa el **Admin SDK**
desde una máquina de confianza (nunca desde el frontend ni desde esta API). Ejemplo con Node.js:

```js
// make-admin.js  —  npm i firebase-admin
const admin = require('firebase-admin');
admin.initializeApp({ credential: admin.credential.cert(require('./serviceAccountKey.json')) });

(async () => {
  const uid = process.argv[2];                       // UID del usuario (Authentication → Users)
  await admin.auth().setCustomUserClaims(uid, { roles: ['ADMIN'] });
  console.log(`${uid} ahora es ADMIN`);
})();
```

```bash
node make-admin.js <UID>
```

- El repo incluye `addAdmin.js` (`node addAdmin.js [email]`), que busca el UID por email y asigna `{ ADMIN: true }`;
  la clave debe estar en `charme-et-chic-firebase-admin-key.json` (ignorada por git).
- `serviceAccountKey.json` se descarga en *Project settings* → *Service accounts* → *Generate new private key*.
  **No lo subas a git** (el `.gitignore` ya lo excluye) ni lo pongas en el frontend.
- Los claims se incorporan al token **la siguiente vez que se renueva**: el usuario debe cerrar sesión y volver a entrar, o
  el frontend llamar a `getIdToken(true)`.
- Alternativa sin código: guardar los roles en Firestore y leerlos desde la API exigiría el Admin SDK en el backend;
  no está implementado porque los custom claims viajan en el token y evitan una consulta por petición.

### Probar sin Firebase (modo local)

Solo para desarrollo. Valida tokens con un secreto compartido (HS256) en lugar de Firebase:

```bash
# Linux / macOS / Git Bash
export JWT_MODE=local
export JWT_LOCAL_SECRET="un-secreto-de-al-menos-32-caracteres!!"
mvn spring-boot:run
```

```powershell
# PowerShell
$env:JWT_MODE = "local"
$env:JWT_LOCAL_SECRET = "un-secreto-de-al-menos-32-caracteres!!"
mvn spring-boot:run
```

Genera un token en https://jwt.io (algoritmo `HS256`, el mismo secreto) con un payload como este:

```json
{ "sub": "usuario-1", "email": "ana@charme.cl", "name": "Ana", "roles": ["ADMIN"], "exp": 1893456000 }
```

(`exp` es un timestamp Unix en segundos; usa una fecha futura. En este modo no se comprueban `iss`/`aud`.) Este modo
**no debe usarse en producción**: la API lo avisa con un `WARN` al arrancar. El *Firebase Auth Emulator* no sirve para
este fin porque emite tokens sin firmar, que la API rechaza por diseño.

---

## Variables de entorno

| Variable | Por defecto | Descripción |
|---|---|---|
| `FIREBASE_PROJECT_ID` | `charme-et-chic` | **Project ID** de Firebase. Define el `iss` y el `aud` que se exigen |
| `JWT_MODE` | `firebase` | `firebase` (producción) o `local` (solo desarrollo) |
| `JWT_LOCAL_SECRET` | *(vacío)* | Secreto HMAC ≥ 32 caracteres; obligatorio si `JWT_MODE=local` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Orígenes permitidos, separados por coma |
| `ORDER_TAX_RATE` | `0.19` | Tasa de IVA aplicada al subtotal de las órdenes |
| `LOW_STOCK_THRESHOLD` | `5` | Umbral de `GET /api/products/low-stock` (stock **menor** a este valor) |

El resto de la configuración está en `src/main/resources/application.yml`.

---

## Conectar con el frontend (Angular)

La API acepta peticiones desde `http://localhost:4200` (CORS). Para otro origen, define `CORS_ALLOWED_ORIGINS`.

**1. Configuración** (`environment.ts`):

```ts
export const environment = {
  apiUrl: 'http://localhost:8080/api',
  firebase: {                       // Firebase Console → Project settings → Your apps → Web
    apiKey: '...',
    authDomain: 'charmeetchic-a1b2c.firebaseapp.com',
    projectId: 'charmeetchic-a1b2c',   // ← el MISMO valor que FIREBASE_PROJECT_ID en el backend
    appId: '...',
  },
};
```

**2. Login** (con `@angular/fire`, API modular):

```ts
import { Auth, signInWithEmailAndPassword, GoogleAuthProvider, signInWithPopup } from '@angular/fire/auth';

await signInWithEmailAndPassword(this.auth, email, password);
// o: await signInWithPopup(this.auth, new GoogleAuthProvider());
```

**3. Enviar el ID token en cada petición a la API** (interceptor):

```ts
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Auth } from '@angular/fire/auth';
import { from, switchMap } from 'rxjs';
import { environment } from '../environments/environment';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const user = inject(Auth).currentUser;
  if (!user || !req.url.startsWith(environment.apiUrl)) return next(req);

  // getIdToken() devuelve el token en caché y lo renueva solo cuando está por expirar
  return from(user.getIdToken()).pipe(
    switchMap(token => next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))),
  );
};
```

Regístralo con `provideHttpClient(withInterceptors([authInterceptor]))`. Los endpoints públicos funcionan con o sin token.

**4. Consumir** (paginación de Spring Data):

```ts
this.http.get<Page<Product>>(`${environment.apiUrl}/products`, {
  params: { page: 0, size: 12, sort: 'name,asc' },
});
```

**5. Saber si el usuario es admin:** llamar a `GET /api/profile` y comprobar `roles.includes('ADMIN')`, o leer los
claims del token con `(await user.getIdTokenResult()).claims['roles']`. La API valida los permisos igualmente en el servidor.

---

## Estructura del proyecto

```
src/main/java/com/charmeetchic/api/
├── CharmeetchicApiApplication.java
├── config/       SecurityConfig · CorsConfig · JwtConfig
├── controller/   Product · Category · Order · Report · Health · Profile
├── dto/          ProductDTO · CategoryDTO · OrderDTO · OrderItemDTO · OrderRequestDTO · OrderStatusDTO
│                 CartItemDTO · ReportDTO · UserDTO
├── entity/       Product · Category · Order · OrderItem · User
├── enums/        OrderStatus · Role
├── exception/    GlobalExceptionHandler · ApiError · InvalidJwt · ResourceNotFound · InsufficientStock
│                 Validation · Conflict
├── filter/       JwtValidationFilter
├── repository/   Product · Category · Order · OrderItem · User
├── security/     AuthenticatedUser (principal del JWT)
└── service/      Product · Category · Order · Report · User · JwtService · JwksKeyProvider
src/main/resources/  application.yml · data.sql
src/test/java/...    tests de servicios (Mockito) y de integración (MockMvc + H2)
```

Notas sobre la estructura:

- El paquete se llama `enums` y no `enum`, porque `enum` es palabra reservada de Java.
- **Dónde vive cada cosa de la autenticación:** `JwtValidationFilter` lee la cabecera y crea la autenticación de Spring
  Security; `JwtService` verifica el token (firma, `iss`, `aud`, `sub`, `exp`); `JwksKeyProvider` descarga y cachea las
  claves públicas de Google; `JwtConfig` lee `firebase.project-id`. `SecurityConfig` define qué rol necesita cada ruta.
- El filtro **no es un `@Component`** a propósito: si lo fuera, Spring Boot lo registraría también como filtro de
  servlet global, ejecutándose fuera de la cadena de seguridad. `SecurityConfig` lo instancia e inserta solo en ella.
- No hay `CorsFilter.java`: la política CORS vive en `CorsConfig` y Spring Security la aplica *antes* de la
  autenticación (necesario para los preflight `OPTIONS`).
- Las rutas de los controladores son `/products`, `/orders`... (sin `/api`) porque el prefijo `/api` ya lo aporta
  `server.servlet.context-path`. Si además se escribiera en los controladores, las URLs serían `/api/api/...`.
- Los datos iniciales están en `data.sql` (no `schema.sql`): con `ddl-auto: create-drop` Hibernate crea las tablas
  y un `schema.sql` con `INSERT` se ejecutaría antes de que existan.

---

## Tests

```bash
mvn test
```

- **Servicios** (`ProductService`, `CategoryService`, `OrderService`, `ReportService`): Mockito, sin base de datos.
- **`JwtService`**: en modo Firebase, con un par RSA generado en el test que hace de "Google" y la descarga de claves
  simulada (`JwksKeyProvider` mockeado). Cubre emisor/audiencia incorrectos, token de otro proyecto, `sub` ausente,
  expirado, `iat`/`auth_time` futuros, firma falsificada, payload manipulado, `kid` desconocido y el ataque de confusión
  HS256 vs RS256; y la lectura de roles desde los tres formatos de custom claims.
- **Integración** (`FirebaseAuthIntegrationTest`): la cadena de seguridad completa en modo Firebase con tokens RS256
  (usuario normal, admin por custom claim, token falsificado, sin firma, HS256, de otro proyecto, expirado).
- **Integración** (`ApiIntegrationTest`, tokens locales HS256): matriz de acceso por rol, flujo completo de órdenes
  (stock, propiedad, estados, cancelación), reportes, validaciones, formato de errores y CORS.

---

## Migración desde Azure AD

Esta API validaba antes tokens de Azure AD. Qué cambió:

| | Azure AD | Firebase |
|---|---|---|
| Configuración | `AZURE_TENANT_ID` + `AZURE_CLIENT_ID` | `FIREBASE_PROJECT_ID` |
| `iss` | `https://login.microsoftonline.com/<tenant>/v2.0` (o `sts.windows.net`) | `https://securetoken.google.com/<project>` |
| `aud` | `<client-id>` / `api://<client-id>` | `<project>` |
| Id de usuario | claim `oid` | claim `sub` (UID de Firebase) |
| Roles | *App roles* (claim `roles`) | *Custom claims* (`roles` / `role` / `admin` / `ADMIN`) |
| Claves de firma | JWKS del tenant de Microsoft | JWKS de `securetoken@system.gserviceaccount.com` |

Las entidades, repositorios, servicios de negocio, controladores y reglas de acceso no cambiaron. Los `userId` guardados
ahora son UIDs de Firebase; con la base H2 en memoria no hay datos previos que migrar (en una base persistente, los
`userId` antiguos —`oid` de Azure— no coincidirían con los nuevos UIDs).

---

## Evolución hacia ecommerce / microservicios

La API está pensada para separarse por dominios sin reescribir la seguridad:

- **Identidad compartida:** el UID de Firebase es el `userId` de las órdenes. Un servicio nuevo (Cart, Checkout,
  Payment) solo necesita el mismo `JwtService` + `SecurityConfig` para validar los mismos tokens; no hace falta
  compartir una base de datos de usuarios.
- **Límites de módulo:** `catalog` (Product/Category), `orders` (Order/OrderItem), `reports` y `users` ya
  interactúan solo a través de servicios y DTOs, por lo que pueden extraerse a servicios independientes.
- **Carrito:** `CartItemDTO` ya modela la línea de carrito; un servicio Cart puede persistirlo y llamar a
  `POST /orders` en el checkout.
- **Pagos:** basta añadir estados a `OrderStatus` (p. ej. `PAID`) y un servicio Payment que confirme la orden.

### Antes de producción

- Sustituir H2 por una base de datos real (PostgreSQL/SQL Server), `ddl-auto: validate` y migraciones con Flyway/Liquibase.
- Desactivar la consola H2 (`spring.h2.console.enabled=false`) y **no** usar `JWT_MODE=local`.
- Verificar que `FIREBASE_PROJECT_ID` (por defecto `charme-et-chic`) coincide con el Project ID del proyecto de Firebase usado por el frontend.
- Servir la API solo por HTTPS y ajustar `CORS_ALLOWED_ORIGINS` al dominio real del frontend.
- Restringir `logging.level.com.charmeetchic` a `INFO`.
