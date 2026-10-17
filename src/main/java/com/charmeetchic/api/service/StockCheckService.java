package com.charmeetchic.api.service;

import com.charmeetchic.api.dto.StockCheckDTO;
import com.charmeetchic.api.exception.GatewayTimeoutException;
import com.charmeetchic.api.exception.ResourceNotFoundException;
import com.charmeetchic.api.exception.ValidationException;
import com.charmeetchic.api.messaging.config.MessagingProperties;
import com.charmeetchic.api.messaging.event.StockQueryReply;
import com.charmeetchic.api.messaging.producer.StockQueryClient;
import org.springframework.stereotype.Service;

/**
 * Lado que PREGUNTA en la consulta síncrona de stock (GET /inventory/stock-check/{sku}).
 *
 * <p><b>Síncrono vs. asíncrono</b> (la diferencia que se defiende):
 * <ul>
 *   <li><b>Aquí (síncrono, RPC):</b> la petición HTTP espera la respuesta del consumidor. Si no contesta en
 *       {@code rpc.timeout-ms}, el cliente recibe 504. Sirve cuando la respuesta se necesita YA y es rápida.</li>
 *   <li><b>Reportes (asíncrono, 202):</b> la petición HTTP solo deja el trabajo en la cola y responde de
 *       inmediato con un id; el resultado se consulta después. Sirve para trabajos lentos o que pueden
 *       reintentarse sin que el usuario espere conectado.</li>
 * </ul>
 */
@Service
public class StockCheckService {

    private final StockQueryClient client;
    private final long timeoutMs;

    public StockCheckService(StockQueryClient client, MessagingProperties props) {
        this.client = client;
        this.timeoutMs = props.rpc().timeoutMs();
    }

    /**
     * @throws ResourceNotFoundException si no hay un producto activo con ese SKU (404)
     * @throws GatewayTimeoutException   si el consumidor no respondió a tiempo (504)
     */
    public StockCheckDTO check(String sku) {
        if (sku == null || sku.isBlank() || sku.length() > 50) {
            throw new ValidationException("El SKU es obligatorio y no puede superar 50 caracteres");
        }
        long start = System.nanoTime();
        StockQueryReply reply = client.query(sku.trim())
                .orElseThrow(() -> new GatewayTimeoutException("El servicio de consulta de stock no respondió en "
                        + timeoutMs + " ms. Intenta de nuevo en unos segundos."));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (!reply.found()) {
            throw new ResourceNotFoundException("No existe un producto activo con SKU '" + reply.sku() + "'");
        }
        return new StockCheckDTO(reply.sku(), reply.productId(), reply.productName(), reply.stock(),
                reply.lowStock(), reply.threshold(), reply.respondedBy(), reply.respondedAt(), elapsedMs);
    }
}
