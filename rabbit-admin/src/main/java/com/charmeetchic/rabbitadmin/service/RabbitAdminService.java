package com.charmeetchic.rabbitadmin.service;

import com.charmeetchic.rabbitadmin.client.RabbitManagementClient;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.BindingDetails;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.ExchangeDetails;
import com.charmeetchic.rabbitadmin.client.RabbitManagementClient.QueueDetails;
import com.charmeetchic.rabbitadmin.config.AppProperties;
import com.charmeetchic.rabbitadmin.dto.BindingRequest;
import com.charmeetchic.rabbitadmin.dto.ExchangeRequest;
import com.charmeetchic.rabbitadmin.dto.QueueRequest;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.BindingInfo;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.Declared;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.ExchangeInfo;
import com.charmeetchic.rabbitadmin.dto.ResourceInfo.QueueInfo;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.ConflictException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.NotFoundException;
import com.charmeetchic.rabbitadmin.exception.AdminExceptions.ProtectedResourceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Toda la lógica de administración de RabbitMQ. Los controladores solo le pasan DTOs ya validados.
 *
 * <ul>
 *   <li><b>Escrituras</b> (declarar/borrar): por AMQP con {@link AmqpAdmin} (implementado por {@code RabbitAdmin}).</li>
 *   <li><b>Lecturas</b> (listar, ver configuración actual): por la API HTTP con {@link RabbitManagementClient},
 *       porque AMQP no ofrece esas operaciones.</li>
 * </ul>
 *
 * <p>Reglas:
 * <ul>
 *   <li><b>Protegidos</b>: los nombres con prefijo de {@code app.admin.protected-prefixes} (recursos de la API de
 *       inventario) no se pueden crear ni borrar -> 403. Tampoco se pueden crear ni borrar bindings HACIA una
 *       cola protegida. Sí se permite unir un exchange del sistema a una cola propia (p. ej. para recibir una
 *       copia de los movimientos de stock): eso no altera la app.</li>
 *   <li><b>Idempotencia</b>: crear algo que ya existe con la MISMA configuración no es error (200, created=false).
 *       Si existe con OTRA configuración -> 409 con la diferencia.</li>
 *   <li><b>Referencias</b>: un binding o un dead-letter exchange deben apuntar a recursos existentes -> si no, 404.</li>
 * </ul>
 */
@Slf4j
@Service
public class RabbitAdminService {

    private static final String DLX_ARG = "x-dead-letter-exchange";
    private static final String DLK_ARG = "x-dead-letter-routing-key";
    private static final String TTL_ARG = "x-message-ttl";
    private static final String MAX_LENGTH_ARG = "x-max-length";
    /** Los argumentos que esta API administra; otros (p. ej. x-queue-type que agrega RabbitMQ) se ignoran al comparar. */
    private static final List<String> MANAGED_ARGS = List.of(DLX_ARG, DLK_ARG, TTL_ARG, MAX_LENGTH_ARG);

    private final AmqpAdmin amqpAdmin;
    private final RabbitManagementClient management;
    private final List<String> protectedPrefixes;

    public RabbitAdminService(AmqpAdmin amqpAdmin, RabbitManagementClient management, AppProperties props) {
        this.amqpAdmin = amqpAdmin;
        this.management = management;
        this.protectedPrefixes = props.admin().protectedPrefixes();
    }

    // =====================================================================================
    // Colas
    // =====================================================================================

    public List<QueueInfo> listQueues() {
        return management.listQueues().stream()
                .map(q -> new QueueInfo(q.name(), q.durable(), q.autoDelete(), q.arguments(), q.messages(),
                        q.consumers(), isProtected(q.name())))
                .toList();
    }

    public Declared<QueueInfo> createQueue(QueueRequest request) {
        assertNotProtected(request.name(), "la cola");
        if (request.deadLetterExchange() != null && management.getExchange(request.deadLetterExchange()).isEmpty()) {
            throw new NotFoundException("El exchange de dead-letter '" + request.deadLetterExchange() + "' no existe");
        }
        Map<String, Object> wanted = queueArguments(request);
        QueueInfo info = new QueueInfo(request.name(), request.durable(), request.autoDelete(), wanted, 0, 0, false);

        var existing = management.getQueue(request.name());
        if (existing.isPresent()) {
            String diff = queueDifferences(existing.get(), request, wanted);
            if (!diff.isEmpty()) {
                throw new ConflictException("La cola '" + request.name() + "' ya existe con otra configuración: " + diff);
            }
            log.info("Cola '{}' ya existía con la misma configuración (sin cambios) [{}]", request.name(), actor());
            return new Declared<>(info, false);
        }

        QueueBuilder builder = request.durable() ? QueueBuilder.durable(request.name())
                : QueueBuilder.nonDurable(request.name());
        if (request.autoDelete()) {
            builder.autoDelete();
        }
        builder.withArguments(wanted);
        declare(() -> amqpAdmin.declareQueue(builder.build()), "la cola '" + request.name() + "'");
        log.info("Cola '{}' creada (durable={}, autoDelete={}, args={}) [{}]", request.name(), request.durable(),
                request.autoDelete(), wanted, actor());
        return new Declared<>(info, true);
    }

    /**
     * Borra una cola.
     *
     * @param ifUnused solo si no tiene consumidores
     * @param ifEmpty  solo si no tiene mensajes
     */
    public void deleteQueue(String name, boolean ifUnused, boolean ifEmpty) {
        assertNotProtected(name, "la cola");
        if (amqpAdmin.getQueueInfo(name) == null) {
            throw new NotFoundException("La cola '" + name + "' no existe");
        }
        try {
            if (ifUnused || ifEmpty) {
                amqpAdmin.deleteQueue(name, ifUnused, ifEmpty);
            } else {
                amqpAdmin.deleteQueue(name);
            }
        } catch (AmqpConnectException e) {
            throw e;
        } catch (AmqpException e) {
            if (isPreconditionFailed(e)) {
                throw new ConflictException("La cola '" + name + "' no se borró porque "
                        + (ifEmpty ? "tiene mensajes" : "") + (ifEmpty && ifUnused ? " o " : "")
                        + (ifUnused ? "tiene consumidores conectados" : ""));
            }
            throw e;
        }
        log.info("Cola '{}' borrada (ifUnused={}, ifEmpty={}) [{}]", name, ifUnused, ifEmpty, actor());
    }

    // =====================================================================================
    // Exchanges
    // =====================================================================================

    /** Exchanges propios y del sistema. Omite el exchange por defecto ("") y los internos "amq.*" de RabbitMQ. */
    public List<ExchangeInfo> listExchanges() {
        return management.listExchanges().stream()
                .filter(e -> !e.name().isEmpty() && !e.name().startsWith("amq."))
                .map(e -> new ExchangeInfo(e.name(), e.type(), e.durable(), e.autoDelete(), isProtected(e.name())))
                .toList();
    }

    public Declared<ExchangeInfo> createExchange(ExchangeRequest request) {
        assertNotProtected(request.name(), "el exchange");
        ExchangeInfo info = new ExchangeInfo(request.name(), request.type(), request.durable(), request.autoDelete(),
                false);

        var existing = management.getExchange(request.name());
        if (existing.isPresent()) {
            String diff = exchangeDifferences(existing.get(), request);
            if (!diff.isEmpty()) {
                throw new ConflictException("El exchange '" + request.name() + "' ya existe con otra configuración: "
                        + diff);
            }
            log.info("Exchange '{}' ya existía con la misma configuración (sin cambios) [{}]", request.name(), actor());
            return new Declared<>(info, false);
        }

        ExchangeBuilder builder = new ExchangeBuilder(request.name(), request.type()).durable(request.durable());
        if (request.autoDelete()) {
            builder.autoDelete();
        }
        Exchange exchange = builder.build();
        declare(() -> amqpAdmin.declareExchange(exchange), "el exchange '" + request.name() + "'");
        log.info("Exchange '{}' ({}) creado (durable={}, autoDelete={}) [{}]", request.name(), request.type(),
                request.durable(), request.autoDelete(), actor());
        return new Declared<>(info, true);
    }

    public void deleteExchange(String name) {
        assertNotProtected(name, "el exchange");
        if (management.getExchange(name).isEmpty()) {
            throw new NotFoundException("El exchange '" + name + "' no existe");
        }
        amqpAdmin.deleteExchange(name);
        log.info("Exchange '{}' borrado (sus bindings se eliminan con él) [{}]", name, actor());
    }

    // =====================================================================================
    // Bindings (exchange -> cola)
    // =====================================================================================

    /** Bindings exchange -> cola. Omite los automáticos del exchange por defecto (uno por cola). */
    public List<BindingInfo> listBindings() {
        return management.listBindings().stream()
                .filter(b -> !b.source().isEmpty() && "queue".equals(b.destinationType()))
                .map(b -> new BindingInfo(b.source(), b.destination(), b.routingKey(), isProtected(b.destination())))
                .toList();
    }

    public Declared<BindingInfo> createBinding(BindingRequest request) {
        assertBindingAllowed(request);
        assertBindingEndsExist(request);
        BindingInfo info = new BindingInfo(request.exchange(), request.queue(), request.routingKey(), false);

        if (bindingExists(request)) {
            log.info("Binding {} -[{}]-> {} ya existía (sin cambios) [{}]", request.exchange(), request.routingKey(),
                    request.queue(), actor());
            return new Declared<>(info, false);
        }
        declare(() -> amqpAdmin.declareBinding(toBinding(request)), "el binding");
        log.info("Binding {} -[{}]-> {} creado [{}]", request.exchange(), request.routingKey(), request.queue(), actor());
        return new Declared<>(info, true);
    }

    public void deleteBinding(BindingRequest request) {
        assertBindingAllowed(request);
        if (!bindingExists(request)) {
            throw new NotFoundException("No existe el binding " + request.exchange() + " -[" + request.routingKey()
                    + "]-> " + request.queue());
        }
        amqpAdmin.removeBinding(toBinding(request));
        log.info("Binding {} -[{}]-> {} borrado [{}]", request.exchange(), request.routingKey(), request.queue(), actor());
    }

    // =====================================================================================
    // Reglas y utilidades
    // =====================================================================================

    /** true si el nombre empieza con un prefijo protegido (recursos de la API de inventario). */
    public boolean isProtected(String name) {
        return name != null && protectedPrefixes.stream().anyMatch(name::startsWith);
    }

    private void assertNotProtected(String name, String what) {
        if (isProtected(name)) {
            throw new ProtectedResourceException("No se puede modificar " + what + " '" + name + "': pertenece a la "
                    + "API de inventario (prefijos protegidos: " + String.join(", ", protectedPrefixes) + ")");
        }
    }

    /** Un binding hacia una cola del sistema podría meterle mensajes ajenos o cortarle los suyos. */
    private void assertBindingAllowed(BindingRequest request) {
        if (isProtected(request.queue())) {
            throw new ProtectedResourceException("No se pueden crear ni borrar bindings hacia la cola '"
                    + request.queue() + "': pertenece a la API de inventario");
        }
    }

    private void assertBindingEndsExist(BindingRequest request) {
        if (amqpAdmin.getQueueInfo(request.queue()) == null) {
            throw new NotFoundException("La cola '" + request.queue() + "' no existe; créala antes del binding");
        }
        if (management.getExchange(request.exchange()).isEmpty()) {
            throw new NotFoundException("El exchange '" + request.exchange() + "' no existe; créalo antes del binding");
        }
    }

    private boolean bindingExists(BindingRequest request) {
        return management.getBindings(request.exchange(), request.queue()).stream()
                .map(BindingDetails::routingKey)
                .anyMatch(request.routingKey()::equals);
    }

    private static Binding toBinding(BindingRequest request) {
        return new Binding(request.queue(), Binding.DestinationType.QUEUE, request.exchange(), request.routingKey(),
                null);
    }

    private static Map<String, Object> queueArguments(QueueRequest r) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (r.deadLetterExchange() != null) {
            args.put(DLX_ARG, r.deadLetterExchange());
        }
        if (r.deadLetterRoutingKey() != null) {
            args.put(DLK_ARG, r.deadLetterRoutingKey());
        }
        if (r.messageTtlMs() != null) {
            args.put(TTL_ARG, r.messageTtlMs());
        }
        if (r.maxLength() != null) {
            args.put(MAX_LENGTH_ARG, r.maxLength());
        }
        return args;
    }

    /** Diferencias entre la cola que existe y la pedida; vacío si son equivalentes. */
    private static String queueDifferences(QueueDetails existing, QueueRequest request, Map<String, Object> wanted) {
        List<String> diffs = new ArrayList<>();
        if (existing.durable() != request.durable()) {
            diffs.add("durable es " + existing.durable() + " (pediste " + request.durable() + ")");
        }
        if (existing.autoDelete() != request.autoDelete()) {
            diffs.add("autoDelete es " + existing.autoDelete() + " (pediste " + request.autoDelete() + ")");
        }
        Map<String, Object> current = existing.arguments() == null ? Map.of() : existing.arguments();
        for (String arg : MANAGED_ARGS) {
            Object have = normalize(current.get(arg));
            Object want = normalize(wanted.get(arg));
            if (!Objects.equals(have, want)) {
                diffs.add(arg + " es " + (have == null ? "(sin definir)" : have)
                        + " (pediste " + (want == null ? "(sin definir)" : want) + ")");
            }
        }
        return String.join("; ", diffs);
    }

    private static String exchangeDifferences(ExchangeDetails existing, ExchangeRequest request) {
        List<String> diffs = new ArrayList<>();
        if (!existing.type().equalsIgnoreCase(request.type())) {
            diffs.add("es de tipo " + existing.type() + " (pediste " + request.type() + ")");
        }
        if (existing.durable() != request.durable()) {
            diffs.add("durable es " + existing.durable() + " (pediste " + request.durable() + ")");
        }
        if (existing.autoDelete() != request.autoDelete()) {
            diffs.add("autoDelete es " + existing.autoDelete() + " (pediste " + request.autoDelete() + ")");
        }
        return String.join("; ", diffs);
    }

    /** JSON trae los números como Integer o Long según el tamaño: se comparan como texto. */
    private static Object normalize(Object value) {
        return value instanceof Number n ? String.valueOf(n.longValue()) : value;
    }

    /** Declara y traduce un PRECONDITION_FAILED de RabbitMQ (argumentos incompatibles) a 409. */
    private static void declare(Runnable declaration, String what) {
        try {
            declaration.run();
        } catch (AmqpConnectException e) {
            throw e;
        } catch (AmqpException e) {
            if (isPreconditionFailed(e)) {
                throw new ConflictException("RabbitMQ rechazó " + what + " por configuración incompatible con la "
                        + "existente: " + rootMessage(e));
            }
            throw e;
        }
    }

    /** RabbitMQ responde PRECONDITION_FAILED (406) cuando los argumentos no calzan o falla un if-empty/if-unused. */
    static boolean isPreconditionFailed(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("PRECONDITION_FAILED")) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    /** UID de Firebase de quien hace la operación, para el log de auditoría. */
    private static String actor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return "uid=" + (auth == null ? "?" : auth.getName());
    }
}
