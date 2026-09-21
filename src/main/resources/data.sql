-- =============================================================================
-- DATOS INICIALES DE DESARROLLO  (se cargan en cada arranque sobre H2 en memoria)
-- =============================================================================
-- Las tablas las crea Hibernate (spring.jpa.hibernate.ddl-auto=create-drop) y luego Spring Boot
-- ejecuta este archivo (spring.jpa.defer-datasource-initialization=true).
-- Por eso se llama data.sql y no schema.sql: un schema.sql con INSERT se ejecutaría ANTES de que
-- existan las tablas y fallaría.
--
-- Montos de ejemplo (sin moneda fija). `price` = precio de venta neto (sin IVA); `cost` = costo.
--
-- CÓMO AGREGAR MÁS DATOS
--  * Categoría nueva:
--      INSERT INTO categories (name, description, created_at)
--      VALUES ('Broches', 'Broches y pasadores', CURRENT_TIMESTAMP);
--  * Producto nuevo: copiar una fila del bloque de productos. Reglas:
--      - `sku` debe ser ÚNICO y en MAYÚSCULAS (la API normaliza los SKU a mayúsculas).
--      - la categoría se referencia por NOMBRE con el subselect (SELECT id FROM categories WHERE name = '...'),
--        así no hay que conocer ids numéricos.
--      - `version` empieza en 0 (bloqueo optimista) y `active` en TRUE.
--  * Imágenes de un producto (varias por producto):
--      INSERT INTO product_images (product_id, image_url)
--      SELECT id, '/images/products/mi-foto.jpg' FROM products WHERE sku = 'MI-SKU';
--  * En producción no se usa este archivo: se recomienda Flyway/Liquibase con una base de datos real.
-- =============================================================================

-- ------------------------------------------------------------------ categorías
INSERT INTO categories (name, description, created_at) VALUES
    ('Collares',    'Collares, gargantillas y colgantes',        CURRENT_TIMESTAMP),
    ('Pulseras',    'Pulseras, brazaletes y tobilleras',         CURRENT_TIMESTAMP),
    ('Anillos',     'Anillos de compromiso, casuales y sets',    CURRENT_TIMESTAMP),
    ('Aretes',      'Aros, argollas y aretes colgantes',         CURRENT_TIMESTAMP),
    ('Accesorios',  'Accesorios para el cabello y complementos', CURRENT_TIMESTAMP);

-- ------------------------------------------------------------------ productos
INSERT INTO products (name, description, price, cost, sku, stock, category_id, active, created_at, updated_at, version) VALUES
    ('Collar Perla Clásico',     'Collar de perlas cultivadas con cierre de plata 925',  24990.00, 11000.00, 'COL-PER-001', 18, (SELECT id FROM categories WHERE name = 'Collares'),   TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Gargantilla Luna Dorada',  'Gargantilla con dije de luna, baño de oro 18k',        15990.00,  6500.00, 'COL-LUN-002', 25, (SELECT id FROM categories WHERE name = 'Collares'),   TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Colgante Corazón Plata',   'Colgante de corazón en plata 925 con cadena de 45 cm', 19990.00,  8200.00, 'COL-COR-003',  4, (SELECT id FROM categories WHERE name = 'Collares'),   TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Pulsera Eslabones Oro',    'Pulsera de eslabones con baño de oro 18k',             17990.00,  7300.00, 'PUL-ESL-001', 20, (SELECT id FROM categories WHERE name = 'Pulseras'),   TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Brazalete Cuarzo Rosa',    'Brazalete elástico con cuentas de cuarzo rosa',        12990.00,  4800.00, 'PUL-CUA-002', 30, (SELECT id FROM categories WHERE name = 'Pulseras'),   TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Pulsera Charms Plata',     'Pulsera de plata 925 con tres charms intercambiables', 29990.00, 13500.00, 'PUL-CHA-003',  3, (SELECT id FROM categories WHERE name = 'Pulseras'),   TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Anillo Solitario Circón',  'Anillo solitario con circón en plata 925',             21990.00,  9000.00, 'ANI-SOL-001', 15, (SELECT id FROM categories WHERE name = 'Anillos'),    TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Anillo Trenzado Oro',      'Anillo trenzado con baño de oro 18k',                  13990.00,  5200.00, 'ANI-TRE-002', 22, (SELECT id FROM categories WHERE name = 'Anillos'),    TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Set Anillos Minimalistas', 'Set de 5 anillos finos apilables',                      9990.00,  3400.00, 'ANI-SET-003', 40, (SELECT id FROM categories WHERE name = 'Anillos'),    TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Aros Argolla Mediana',     'Argollas de acero quirúrgico bañadas en oro',           8990.00,  3000.00, 'ARE-ARG-001', 50, (SELECT id FROM categories WHERE name = 'Aretes'),     TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Aretes Perla Colgantes',   'Aretes colgantes con perla de río y gancho de plata',  16990.00,  6900.00, 'ARE-PER-002', 12, (SELECT id FROM categories WHERE name = 'Aretes'),     TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Aretes Gota Cristal',      'Aretes con cristal en forma de gota',                  11990.00,  4100.00, 'ARE-GOT-003',  2, (SELECT id FROM categories WHERE name = 'Aretes'),     TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Pinza Cabello Nácar',      'Pinza para el cabello con detalle de nácar',            6990.00,  2200.00, 'ACC-PIN-001', 35, (SELECT id FROM categories WHERE name = 'Accesorios'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Cinta Satín Perlas',       'Cinta de satín con perlas decorativas',                 4990.00,  1500.00, 'ACC-CIN-002', 60, (SELECT id FROM categories WHERE name = 'Accesorios'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('Estuche Joyero Viaje',     'Estuche joyero de viaje con cierre y espejo',          14990.00,  5600.00, 'ACC-EST-003',  8, (SELECT id FROM categories WHERE name = 'Accesorios'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- ------------------------------------------------------------------ imágenes (rutas de ejemplo)
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/collar-perla-clasico.jpg'     FROM products WHERE sku = 'COL-PER-001';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/collar-perla-clasico-2.jpg'   FROM products WHERE sku = 'COL-PER-001';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/gargantilla-luna.jpg'         FROM products WHERE sku = 'COL-LUN-002';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/colgante-corazon.jpg'         FROM products WHERE sku = 'COL-COR-003';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/pulsera-eslabones.jpg'        FROM products WHERE sku = 'PUL-ESL-001';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/brazalete-cuarzo.jpg'         FROM products WHERE sku = 'PUL-CUA-002';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/pulsera-charms.jpg'           FROM products WHERE sku = 'PUL-CHA-003';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/anillo-solitario.jpg'         FROM products WHERE sku = 'ANI-SOL-001';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/anillo-trenzado.jpg'          FROM products WHERE sku = 'ANI-TRE-002';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/set-anillos.jpg'              FROM products WHERE sku = 'ANI-SET-003';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/aros-argolla.jpg'             FROM products WHERE sku = 'ARE-ARG-001';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/aretes-perla.jpg'             FROM products WHERE sku = 'ARE-PER-002';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/aretes-gota.jpg'              FROM products WHERE sku = 'ARE-GOT-003';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/pinza-nacar.jpg'              FROM products WHERE sku = 'ACC-PIN-001';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/cinta-satin.jpg'              FROM products WHERE sku = 'ACC-CIN-002';
INSERT INTO product_images (product_id, image_url) SELECT id, '/images/products/estuche-joyero.jpg'           FROM products WHERE sku = 'ACC-EST-003';
