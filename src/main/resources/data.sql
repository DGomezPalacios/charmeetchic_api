-- =============================================================================
-- DATOS INICIALES (catálogo de ejemplo)
-- =============================================================================
-- Las tablas las crea Hibernate (ddl-auto) y luego Spring Boot ejecuta este archivo
-- (spring.jpa.defer-datasource-initialization=true). Por eso se llama data.sql y no schema.sql: un
-- schema.sql con INSERT se ejecutaría ANTES de que existan las tablas y fallaría.
--
-- Se usa en dos bases:
--  * H2 en memoria (desarrollo y tests): se carga en cada arranque sobre una base vacía.
--  * PostgreSQL (perfil "k8s", ver application-k8s.yml): la base persiste y la comparten todas las
--    réplicas, así que este archivo se ejecuta en CADA arranque de CADA pod sobre una base que ya tiene datos.
--
-- Por eso es IDEMPOTENTE y PORTABLE:
--  * Cada INSERT lleva WHERE NOT EXISTS sobre la clave natural (nombre de categoría, SKU, producto+imagen):
--    si la fila ya existe no se vuelve a insertar. No se usa MERGE (solo H2) ni ON CONFLICT (solo PostgreSQL).
--  * Nunca se indican ids: los asigna la columna IDENTITY, así la secuencia de PostgreSQL queda sincronizada
--    y un producto creado después por la API no choca con un id ya usado.
--  * Las filas van en una tabla derivada (VALUES ... AS v(columnas)), SQL estándar que aceptan ambas bases.
--    La columna `ord` + ORDER BY mantiene el orden de inserción (y por lo tanto los ids) de siempre.
--
-- Montos de ejemplo (sin moneda fija). `price` = precio de venta neto (sin IVA); `cost` = costo.
--
-- CÓMO AGREGAR MÁS DATOS: agregar una fila al VALUES del bloque que corresponda, con el siguiente `ord`.
--  * Producto: `sku` ÚNICO y en MAYÚSCULAS (la API normaliza los SKU a mayúsculas); la categoría se indica
--    por NOMBRE (el JOIN busca su id). `version` empieza en 0 (bloqueo optimista) y `active` en TRUE.
--  * Imagen: SKU del producto + ruta; un producto puede tener varias.
--  * En producción real se recomienda Flyway/Liquibase en vez de este archivo.
-- =============================================================================

-- ------------------------------------------------------------------ categorías
INSERT INTO categories (name, description, created_at)
SELECT v.name, v.description, CURRENT_TIMESTAMP
FROM (VALUES
    (1, 'Collares',    'Collares, gargantillas y colgantes'),
    (2, 'Pulseras',    'Pulseras, brazaletes y tobilleras'),
    (3, 'Anillos',     'Anillos de compromiso, casuales y sets'),
    (4, 'Aretes',      'Aros, argollas y aretes colgantes'),
    (5, 'Accesorios',  'Accesorios para el cabello y complementos')
) AS v(ord, name, description)
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.name = v.name)
ORDER BY v.ord;

-- ------------------------------------------------------------------ productos
INSERT INTO products (name, description, price, cost, sku, stock, category_id, active, created_at, updated_at, version)
SELECT v.name, v.description, v.price, v.cost, v.sku, v.stock, c.id, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
FROM (VALUES
    ( 1, 'Collar Perla Clásico',     'Collar de perlas cultivadas con cierre de plata 925',  24990.00, 11000.00, 'COL-PER-001', 18, 'Collares'),
    ( 2, 'Gargantilla Luna Dorada',  'Gargantilla con dije de luna, baño de oro 18k',        15990.00,  6500.00, 'COL-LUN-002', 25, 'Collares'),
    ( 3, 'Colgante Corazón Plata',   'Colgante de corazón en plata 925 con cadena de 45 cm', 19990.00,  8200.00, 'COL-COR-003',  4, 'Collares'),
    ( 4, 'Pulsera Eslabones Oro',    'Pulsera de eslabones con baño de oro 18k',             17990.00,  7300.00, 'PUL-ESL-001', 20, 'Pulseras'),
    ( 5, 'Brazalete Cuarzo Rosa',    'Brazalete elástico con cuentas de cuarzo rosa',        12990.00,  4800.00, 'PUL-CUA-002', 30, 'Pulseras'),
    ( 6, 'Pulsera Charms Plata',     'Pulsera de plata 925 con tres charms intercambiables', 29990.00, 13500.00, 'PUL-CHA-003',  3, 'Pulseras'),
    ( 7, 'Anillo Solitario Circón',  'Anillo solitario con circón en plata 925',             21990.00,  9000.00, 'ANI-SOL-001', 15, 'Anillos'),
    ( 8, 'Anillo Trenzado Oro',      'Anillo trenzado con baño de oro 18k',                  13990.00,  5200.00, 'ANI-TRE-002', 22, 'Anillos'),
    ( 9, 'Set Anillos Minimalistas', 'Set de 5 anillos finos apilables',                      9990.00,  3400.00, 'ANI-SET-003', 40, 'Anillos'),
    (10, 'Aros Argolla Mediana',     'Argollas de acero quirúrgico bañadas en oro',           8990.00,  3000.00, 'ARE-ARG-001', 50, 'Aretes'),
    (11, 'Aretes Perla Colgantes',   'Aretes colgantes con perla de río y gancho de plata',  16990.00,  6900.00, 'ARE-PER-002', 12, 'Aretes'),
    (12, 'Aretes Gota Cristal',      'Aretes con cristal en forma de gota',                  11990.00,  4100.00, 'ARE-GOT-003',  2, 'Aretes'),
    (13, 'Pinza Cabello Nácar',      'Pinza para el cabello con detalle de nácar',            6990.00,  2200.00, 'ACC-PIN-001', 35, 'Accesorios'),
    (14, 'Cinta Satín Perlas',       'Cinta de satín con perlas decorativas',                 4990.00,  1500.00, 'ACC-CIN-002', 60, 'Accesorios'),
    (15, 'Estuche Joyero Viaje',     'Estuche joyero de viaje con cierre y espejo',          14990.00,  5600.00, 'ACC-EST-003',  8, 'Accesorios')
) AS v(ord, name, description, price, cost, sku, stock, category)
JOIN categories c ON c.name = v.category
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.sku = v.sku)
ORDER BY v.ord;

-- ------------------------------------------------------------------ imágenes (rutas de ejemplo)
INSERT INTO product_images (product_id, image_url)
SELECT p.id, v.image_url
FROM (VALUES
    ( 1, 'COL-PER-001', '/images/products/collar-perla-clasico.jpg'),
    ( 2, 'COL-PER-001', '/images/products/collar-perla-clasico-2.jpg'),
    ( 3, 'COL-LUN-002', '/images/products/gargantilla-luna.jpg'),
    ( 4, 'COL-COR-003', '/images/products/colgante-corazon.jpg'),
    ( 5, 'PUL-ESL-001', '/images/products/pulsera-eslabones.jpg'),
    ( 6, 'PUL-CUA-002', '/images/products/brazalete-cuarzo.jpg'),
    ( 7, 'PUL-CHA-003', '/images/products/pulsera-charms.jpg'),
    ( 8, 'ANI-SOL-001', '/images/products/anillo-solitario.jpg'),
    ( 9, 'ANI-TRE-002', '/images/products/anillo-trenzado.jpg'),
    (10, 'ANI-SET-003', '/images/products/set-anillos.jpg'),
    (11, 'ARE-ARG-001', '/images/products/aros-argolla.jpg'),
    (12, 'ARE-PER-002', '/images/products/aretes-perla.jpg'),
    (13, 'ARE-GOT-003', '/images/products/aretes-gota.jpg'),
    (14, 'ACC-PIN-001', '/images/products/pinza-nacar.jpg'),
    (15, 'ACC-CIN-002', '/images/products/cinta-satin.jpg'),
    (16, 'ACC-EST-003', '/images/products/estuche-joyero.jpg')
) AS v(ord, sku, image_url)
JOIN products p ON p.sku = v.sku
WHERE NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = v.image_url)
ORDER BY v.ord;
