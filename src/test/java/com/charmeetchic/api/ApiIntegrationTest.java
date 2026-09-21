package com.charmeetchic.api;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba de extremo a extremo con el contexto real (H2 + datos de data.sql + cadena de seguridad).
 * Los tokens se firman en modo LOCAL (HS256); la verificación con formato Firebase (RS256, issuer,
 * audience) se prueba en {@link FirebaseAuthIntegrationTest}. MockMvc no aplica el context-path {@code /api}, por eso
 * las rutas aquí son {@code /products}, no {@code /api/products}.
 *
 * <p>El contexto (y su base de datos) se comparte entre los tests, así que cada uno usa datos propios
 * y hace aserciones que no dependen del orden de ejecución.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.jwt.mode=local",
        "app.jwt.local-secret=integration-test-secret-integration-test-1234"
})
class ApiIntegrationTest {

    private static final String SECRET = "integration-test-secret-integration-test-1234";

    @Autowired
    private MockMvc mvc;

    // ------------------------------------------------------------------ endpoints públicos

    @Test
    void health_isPublic() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.service", is("charmeetchic-api")));
    }

    @Test
    void products_listIsPublic_paginatedAndSorted() throws Exception {
        mvc.perform(get("/products").param("page", "0").param("size", "5").param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()", is(5)))
                .andExpect(jsonPath("$.size", is(5)))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(15)))
                .andExpect(jsonPath("$.content[0].sku", is("PUL-CHA-003"))) // el más caro de los datos iniciales
                .andExpect(jsonPath("$.content[0].categoryName", is("Pulseras")))
                .andExpect(jsonPath("$.content[0].images.length()", is(1)));
    }

    @Test
    void products_searchByTextAndCategory_isPublic() throws Exception {
        mvc.perform(get("/products/search").param("q", "PERLA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sku", hasItem("COL-PER-001")))
                .andExpect(jsonPath("$.content[*].sku", hasItem("ARE-PER-002")));

        mvc.perform(get("/products/search").param("category", "3").param("q", "anillo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].categoryName", hasItem("Anillos")));
    }

    @Test
    void products_getByIdIsPublic_andUnknownIdIs404WithApiErrorBody() throws Exception {
        mvc.perform(get("/products/1")).andExpect(status().isOk()).andExpect(jsonPath("$.id", is(1)));

        mvc.perform(get("/products/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", is("Producto con id 99999 no encontrado")))
                .andExpect(jsonPath("$.path", is("/products/99999")));
    }

    @Test
    void products_invalidSortProperty_is400() throws Exception {
        mvc.perform(get("/products").param("sort", "noExiste,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Propiedad de ordenamiento inválida: noExiste")));
    }

    @Test
    void categories_listIsPublic() throws Exception {
        mvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Collares")))
                .andExpect(jsonPath("$[*].name", hasItem("Accesorios")));
    }

    // ------------------------------------------------------------------ autenticación

    @Test
    void protectedEndpoint_withoutToken_is401_withJsonBody() throws Exception {
        mvc.perform(get("/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.error", is("Unauthorized")));
    }

    @Test
    void invalidToken_is401_evenOnPublicEndpoints() throws Exception {
        mvc.perform(get("/products").header("Authorization", "Bearer no-es-un-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Token de Firebase inválido")));
    }

    @Test
    void expiredToken_is401() throws Exception {
        mvc.perform(get("/orders").header("Authorization", "Bearer " + token("u1", -3600, "USER")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("ha expirado")));
    }

    @Test
    void profile_returnsIdentityFromToken_andRegistersUser() throws Exception {
        mvc.perform(auth(get("/profile"), token("profile-user", 3600, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is("profile-user")))
                .andExpect(jsonPath("$.email", is("profile-user@charme.cl")))
                .andExpect(jsonPath("$.roles", hasItem("ADMIN")))
                .andExpect(jsonPath("$.roles", hasItem("USER")));

        mvc.perform(get("/profile")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ autorización por rol

    @Test
    void adminEndpoints_rejectAnonymous401_user403_admin200() throws Exception {
        for (String path : List.of("/products/low-stock", "/reports/sales", "/reports/inventory-value",
                "/reports/sales-by-category", "/reports/top-products")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(auth(get(path), userToken("u1"))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status", is(403)));
            mvc.perform(auth(get(path), adminToken())).andExpect(status().isOk());
        }
    }

    @Test
    void lowStock_returnsProductsBelowThreshold_mostCriticalFirst() throws Exception {
        mvc.perform(auth(get("/products/low-stock"), adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].sku", hasItem("ARE-GOT-003"))) // stock 2
                .andExpect(jsonPath("$[*].sku", hasItem("PUL-CHA-003"))) // stock 3
                .andExpect(jsonPath("$[*].sku", hasItem("COL-COR-003"))); // stock 4
    }

    @Test
    void writeOperationsOnCatalog_requireAdmin() throws Exception {
        String body = productJson("USER-TRY", "Producto", 1);

        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(auth(post("/products"), userToken("u1")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(auth(put("/products/1"), userToken("u1")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(auth(delete("/products/1"), userToken("u1"))).andExpect(status().isForbidden());
        mvc.perform(auth(post("/categories"), userToken("u1")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ CRUD de productos (ADMIN)

    @Test
    void productLifecycle_create_update_softDelete() throws Exception {
        // crear
        MvcResult created = mvc.perform(auth(post("/products"), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("lifecycle-001", "Producto Ciclo", 10)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.sku", is("LIFECYCLE-001"))) // normalizado a mayúsculas
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.version", is(0)))
                .andReturn();
        long id = extractLong(created, "\"id\":(\\d+)");

        // SKU duplicado -> 409
        mvc.perform(auth(post("/products"), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("LIFECYCLE-001", "Otro", 1)))
                .andExpect(status().isConflict());

        // actualizar con la versión correcta
        mvc.perform(auth(put("/products/" + id), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("LIFECYCLE-001", "Producto Ciclo v2", 25).replace("{", "{\"version\":0,")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Producto Ciclo v2")))
                .andExpect(jsonPath("$.stock", is(25)))
                .andExpect(jsonPath("$.version", is(1)));

        // reutilizar la versión vieja (0) -> 409 por concurrencia
        mvc.perform(auth(put("/products/" + id), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("LIFECYCLE-001", "Editor lento", 99).replace("{", "{\"version\":0,")))
                .andExpect(status().isConflict());

        // borrado lógico: desaparece del catálogo público
        mvc.perform(auth(delete("/products/" + id), adminToken())).andExpect(status().isNoContent());
        mvc.perform(get("/products/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void createProduct_withInvalidBody_is400_withFieldErrors() throws Exception {
        String invalid = """
                {"name":"", "price":-5, "cost":10, "sku":"", "stock":-1, "categoryId":1}""";

        mvc.perform(auth(post("/products"), adminToken()).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Datos de entrada inválidos")))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.price").exists())
                .andExpect(jsonPath("$.fieldErrors.sku").exists())
                .andExpect(jsonPath("$.fieldErrors.stock").exists());
    }

    @Test
    void createProduct_withMalformedJson_is400() throws Exception {
        mvc.perform(auth(post("/products"), adminToken()).contentType(MediaType.APPLICATION_JSON).content("{no-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("El cuerpo de la solicitud es inválido o está mal formado")));
    }

    @Test
    void createProduct_withUnknownCategory_is404() throws Exception {
        mvc.perform(auth(post("/products"), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("NO-CAT-1", "Sin categoría", 1).replace("\"categoryId\":1", "\"categoryId\":9999")))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ categorías (ADMIN)

    @Test
    void categoryLifecycle_andCannotDeleteCategoryWithProducts() throws Exception {
        MvcResult created = mvc.perform(auth(post("/categories"), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Temporal\",\"description\":\"para test\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long id = extractLong(created, "\"id\":(\\d+)");

        mvc.perform(auth(post("/categories"), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"temporal\"}"))
                .andExpect(status().isConflict()); // nombre duplicado sin distinguir mayúsculas

        mvc.perform(auth(delete("/categories/" + id), adminToken())).andExpect(status().isNoContent());
        mvc.perform(get("/categories/" + id)).andExpect(status().isNotFound());

        // "Collares" (id 1) tiene productos -> no se puede borrar
        mvc.perform(auth(delete("/categories/1"), adminToken())).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ órdenes

    @Test
    void orderFlow_create_ownership_status_cancel() throws Exception {
        long productId = 14; // Cinta Satín Perlas (ACC-CIN-002), stock inicial 60 y no usado por otros tests
        int stockBefore = currentStock(productId);
        String alice = userToken("alice");
        String bob = userToken("bob");

        // Alice compra 2 unidades (precio 4990.00 -> subtotal 9980.00, IVA 19% = 1896.20)
        MvcResult created = mvc.perform(auth(post("/orders"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shippingAddress":"Av. Providencia 1234, Santiago",
                                 "items":[{"productId":14,"quantity":1},{"productId":14,"quantity":1}]}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.userId", is("alice")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].quantity", is(2)))
                .andExpect(jsonPath("$.subtotal", is(9980.00)))
                .andExpect(jsonPath("$.tax", is(1896.20)))
                .andExpect(jsonPath("$.total", is(11876.20)))
                .andReturn();
        long orderId = extractLong(created, "^\\{\"id\":(\\d+)");
        assertThat(currentStock(productId)).isEqualTo(stockBefore - 2);

        // Alice ve su orden en el listado y por id; Bob no puede verla ni cancelarla
        mvc.perform(auth(get("/orders"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is((int) orderId)));
        mvc.perform(auth(get("/orders"), bob)).andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(0)));
        mvc.perform(auth(get("/orders/" + orderId), alice)).andExpect(status().isOk());
        mvc.perform(auth(get("/orders/" + orderId), bob)).andExpect(status().isForbidden());
        mvc.perform(auth(delete("/orders/" + orderId), bob)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/orders/" + orderId), adminToken())).andExpect(status().isOk());

        // Solo ADMIN cambia el estado; y solo al siguiente paso
        String statusBody = "{\"status\":\"PROCESSING\"}";
        mvc.perform(auth(put("/orders/" + orderId + "/status"), alice).contentType(MediaType.APPLICATION_JSON).content(statusBody))
                .andExpect(status().isForbidden());
        mvc.perform(auth(put("/orders/" + orderId + "/status"), adminToken()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(put("/orders/" + orderId + "/status"), adminToken()).contentType(MediaType.APPLICATION_JSON).content(statusBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")));

        // Ya no está PENDING: no se puede cancelar
        mvc.perform(auth(delete("/orders/" + orderId), alice)).andExpect(status().isConflict());

        // Una segunda orden PENDING sí se cancela y devuelve el stock
        MvcResult second = mvc.perform(auth(post("/orders"), alice).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"Otra dirección 55\",\"items\":[{\"productId\":14,\"quantity\":5}]}"))
                .andExpect(status().isCreated()).andReturn();
        long secondId = extractLong(second, "^\\{\"id\":(\\d+)");
        assertThat(currentStock(productId)).isEqualTo(stockBefore - 7);

        mvc.perform(auth(delete("/orders/" + secondId), alice)).andExpect(status().isNoContent());
        assertThat(currentStock(productId)).isEqualTo(stockBefore - 2);
        mvc.perform(auth(get("/orders/" + secondId), alice)).andExpect(status().isNotFound());
    }

    @Test
    void createOrder_withMoreThanAvailableStock_is409_andStockIsUntouched() throws Exception {
        long productId = 12; // Aretes Gota Cristal (stock inicial 2)
        int stockBefore = currentStock(productId);

        mvc.perform(auth(post("/orders"), userToken("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"Calle 1\",\"items\":[{\"productId\":12,\"quantity\":" + (stockBefore + 1) + "}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Stock insuficiente")));

        assertThat(currentStock(productId)).isEqualTo(stockBefore);
    }

    @Test
    void createOrder_validation_is400() throws Exception {
        mvc.perform(auth(post("/orders"), userToken("dave")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"\",\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.shippingAddress").exists())
                .andExpect(jsonPath("$.fieldErrors.items").exists());

        mvc.perform(auth(post("/orders"), userToken("dave")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"Calle\",\"items\":[{\"productId\":1,\"quantity\":0}]}"))
                .andExpect(status().isBadRequest());

        mvc.perform(auth(post("/orders"), userToken("dave")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"Calle\",\"items\":[{\"productId\":99999,\"quantity\":1}]}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ reportes

    @Test
    void reports_reflectSales() throws Exception {
        // una venta de un producto exclusivo de este test (ACC-PIN-001, id 13)
        mvc.perform(auth(post("/orders"), userToken("report-buyer")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddress\":\"Calle Reportes 1\",\"items\":[{\"productId\":13,\"quantity\":3}]}"))
                .andExpect(status().isCreated());

        mvc.perform(auth(get("/reports/sales"), adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalSales", greaterThanOrEqualTo(24000.0)));

        mvc.perform(auth(get("/reports/sales-by-category"), adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].category", hasItem("Accesorios")));

        mvc.perform(auth(get("/reports/top-products").param("limit", "3"), adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", org.hamcrest.Matchers.lessThanOrEqualTo(3)))
                .andExpect(jsonPath("$[0].unitsSold", greaterThanOrEqualTo(1)));

        mvc.perform(auth(get("/reports/inventory-value"), adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProducts", greaterThanOrEqualTo(14)))
                .andExpect(jsonPath("$.retailValue", greaterThanOrEqualTo(1.0)));
    }

    @Test
    void reports_validateParameters() throws Exception {
        mvc.perform(auth(get("/reports/sales").param("from", "2026-02-01").param("to", "2026-01-01"), adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("'from' no puede ser posterior a 'to'")));

        mvc.perform(auth(get("/reports/sales").param("from", "31-12-2025"), adminToken()))
                .andExpect(status().isBadRequest());

        mvc.perform(auth(get("/reports/top-products").param("limit", "500"), adminToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.limit").exists());
    }

    // ------------------------------------------------------------------ CORS / errores genéricos

    @Test
    void cors_allowsAngularDevServer_andRejectsOtherOrigins() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/products")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/products")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRoute_is404_inApiErrorFormat_forAuthenticatedUsers() throws Exception {
        mvc.perform(auth(get("/no-existe"), userToken("u1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", is("Recurso no encontrado")));
    }

    @Test
    void wrongHttpMethod_is405_forAuthenticatedUsers() throws Exception {
        mvc.perform(auth(post("/health"), userToken("u1"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/health")).andExpect(status().isUnauthorized()); // anónimo: la regla por defecto es "autenticado"
    }

    // ------------------------------------------------------------------ helpers

    private int currentStock(long productId) throws Exception {
        MvcResult result = mvc.perform(get("/products/" + productId)).andExpect(status().isOk()).andReturn();
        return (int) extractLong(result, "\"stock\":(\\d+)");
    }

    private static long extractLong(MvcResult result, String regex) throws Exception {
        Matcher matcher = Pattern.compile(regex).matcher(result.getResponse().getContentAsString());
        if (!matcher.find()) {
            throw new AssertionError("No se encontró " + regex + " en " + result.getResponse().getContentAsString());
        }
        return Long.parseLong(matcher.group(1));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static String productJson(String sku, String name, int stock) {
        return "{\"name\":\"" + name + "\",\"description\":\"desc\",\"price\":19990.00,\"cost\":8000.00,"
                + "\"sku\":\"" + sku + "\",\"stock\":" + stock + ",\"categoryId\":1,\"images\":[\"/img/a.jpg\"]}";
    }

    private static String adminToken() {
        return token("admin-1", 3600, "ADMIN");
    }

    private static String userToken(String uid) {
        return token(uid, 3600, "USER");
    }

    /** Token con la forma del ID token de Firebase que usa la API (uid en {@code sub}, roles en custom claims). */
    private static String token(String uid, long ttlSeconds, String... roles) {
        return Jwts.builder()
                .subject(uid)
                .claim("email", uid + "@charme.cl").claim("name", "Usuario " + uid)
                .claim("roles", List.of(roles))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlSeconds * 1000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
