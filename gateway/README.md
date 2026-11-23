# charmeetchic-gateway

API Gateway con **Spring Cloud Gateway** (reactivo, Netty). App separada, igual que `rabbit-admin/`: su propio
`pom.xml`, `Dockerfile` y contenedor. En producción está detrás de Caddy (HTTPS) y es la única entrada a
los servicios.

| Ruta pública | Destino | Nota |
|---|---|---|
| `/api/**` | `backend:8080/api/**` | sin cambios en la ruta (el backend usa context-path `/api`) |
| `/admin/**` | `rabbit-admin:8081/**` | `StripPrefix=1`: `/admin/queues` → `/queues` |
| `/health` | el propio gateway | healthcheck del contenedor |

## Qué hace con cada petición

1. **CORS**: un preflight `OPTIONS` del front se responde aquí (200 si el origen está permitido, 403 si no) y
   no llega a los servicios. Orígenes: `CORS_ALLOWED_ORIGINS` (Firebase Hosting + `http://localhost:4200`).
2. **Token**: valida el ID token de Firebase como OAuth2 Resource Server: firma RS256 con el JWKS de Google,
   `exp`, `iss = https://securetoken.google.com/charme-et-chic`, `aud = charme-et-chic`, `sub`.
   Si falta o es inválido → **401 JSON y la petición no se reenvía**.
3. **Rutas públicas**: solo `/health` y `/api/health` pasan sin token (`GATEWAY_PUBLIC_PATHS`).
4. **Reenvío**: el header `Authorization` viaja intacto (cada servicio vuelve a validar y decide los roles).
   Se quita `Origin` para que el backend no agregue un segundo `Access-Control-Allow-Origin`.

## Defensa en profundidad: ¿por qué validar en el gateway Y en el backend?

- **El gateway** filtra en el borde: un token falso, vencido o de otro proyecto no consume recursos del
  backend ni llega a su código. Además centraliza CORS y es el único punto expuesto.
- **El backend** no confía en que alguien ya validó: si en el futuro se publicara su puerto por error, se
  cambiara una ruta del gateway o otro servicio de la red interna lo llamara, sigue exigiendo un token válido.
  Y solo él sabe **qué** puede hacer cada usuario (roles USER/ADMIN, dueño de una orden).
- Así una sola falla de configuración no deja el sistema abierto. El costo es verificar la firma dos veces:
  pocos milisegundos, porque las claves públicas se cachean.

## Ejecutar y probar

```bash
cd gateway
mvn verify            # 13 tests: 401 antes del backend, rutas, StripPrefix, CORS (sin servicios reales)
mvn spring-boot:run   # puerto 8000; BACKEND_URL=http://localhost:8080 RABBIT_ADMIN_URL=http://localhost:8081
```

| Variable | Por defecto | Descripción |
|---|---|---|
| `PORT` | `8000` | Puerto HTTP |
| `BACKEND_URL` / `RABBIT_ADMIN_URL` | `http://localhost:8080` / `:8081` | Destinos |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | Orígenes del front, separados por coma |
| `GATEWAY_PUBLIC_PATHS` | `/health,/api/health` | Rutas sin token. Para abrir el catálogo: agregar `/api/products/**` |
| `FIREBASE_PROJECT_ID` | `charme-et-chic` | Issuer y audience |
| `FIREBASE_JWK_SET_URI` | JWKS de Google | Solo se cambia para pruebas locales |
