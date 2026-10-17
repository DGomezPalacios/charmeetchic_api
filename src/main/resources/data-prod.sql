-- =============================================================================
-- DATOS INICIALES DE PRODUCCIÓN (PostgreSQL) — perfil "prod"
-- =============================================================================
-- Mismos datos que data.sql, pero IDEMPOTENTE: se ejecuta en cada arranque y no duplica nada.
--   * categorías y productos: ON CONFLICT DO NOTHING (name y sku son únicos)
--   * imágenes: solo si esa imagen aún no está asociada al producto (NOT EXISTS)
-- Así la base de datos de la VM tiene catálogo desde el primer arranque y los cambios que se hagan
-- desde la app (stock, precios, productos nuevos) NO se pisan al reiniciar.
--
-- Generado a partir de data.sql. Si agregas datos allá, agrégalos aquí con la misma forma.
-- =============================================================================

-- ------------------------------------------------------------------ categorías
INSERT INTO categories (name, description, created_at) VALUES
    ('Collares',    'Collares, gargantillas y colgantes',        CURRENT_TIMESTAMP),
    ('Pulseras',    'Pulseras, brazaletes y tobilleras',         CURRENT_TIMESTAMP),
    ('Anillos',     'Anillos de compromiso, casuales y sets',    CURRENT_TIMESTAMP),
    ('Aretes',      'Aros, argollas y aretes colgantes',         CURRENT_TIMESTAMP),
    ('Accesorios',  'Accesorios para el cabello y complementos', CURRENT_TIMESTAMP)
    ON CONFLICT (name) DO NOTHING;

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
    ('Estuche Joyero Viaje',     'Estuche joyero de viaje con cierre y espejo',          14990.00,  5600.00, 'ACC-EST-003',  8, (SELECT id FROM categories WHERE name = 'Accesorios'), TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
    ON CONFLICT (sku) DO NOTHING;

-- ------------------------------------------------------------------ imágenes (rutas de ejemplo)
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/collar-perla-clasico.jpg' FROM products p WHERE p.sku = 'COL-PER-001'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/collar-perla-clasico.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/collar-perla-clasico-2.jpg' FROM products p WHERE p.sku = 'COL-PER-001'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/collar-perla-clasico-2.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/gargantilla-luna.jpg' FROM products p WHERE p.sku = 'COL-LUN-002'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/gargantilla-luna.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/colgante-corazon.jpg' FROM products p WHERE p.sku = 'COL-COR-003'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/colgante-corazon.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/pulsera-eslabones.jpg' FROM products p WHERE p.sku = 'PUL-ESL-001'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/pulsera-eslabones.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/brazalete-cuarzo.jpg' FROM products p WHERE p.sku = 'PUL-CUA-002'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/brazalete-cuarzo.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/pulsera-charms.jpg' FROM products p WHERE p.sku = 'PUL-CHA-003'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/pulsera-charms.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/anillo-solitario.jpg' FROM products p WHERE p.sku = 'ANI-SOL-001'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/anillo-solitario.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/anillo-trenzado.jpg' FROM products p WHERE p.sku = 'ANI-TRE-002'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/anillo-trenzado.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/set-anillos.jpg' FROM products p WHERE p.sku = 'ANI-SET-003'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/set-anillos.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/aros-argolla.jpg' FROM products p WHERE p.sku = 'ARE-ARG-001'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/aros-argolla.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/aretes-perla.jpg' FROM products p WHERE p.sku = 'ARE-PER-002'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/aretes-perla.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/aretes-gota.jpg' FROM products p WHERE p.sku = 'ARE-GOT-003'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/aretes-gota.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/pinza-nacar.jpg' FROM products p WHERE p.sku = 'ACC-PIN-001'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/pinza-nacar.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/cinta-satin.jpg' FROM products p WHERE p.sku = 'ACC-CIN-002'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/cinta-satin.jpg');
INSERT INTO product_images (product_id, image_url) SELECT p.id, '/images/products/estuche-joyero.jpg' FROM products p WHERE p.sku = 'ACC-EST-003'
    AND NOT EXISTS (SELECT 1 FROM product_images i WHERE i.product_id = p.id AND i.image_url = '/images/products/estuche-joyero.jpg');
