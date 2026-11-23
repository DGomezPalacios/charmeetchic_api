package com.charmeetchic.api.streaming.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Configuración de Kafka leída de {@code app.streaming} en application.yml (único lugar con nombres de
 * tópicos, particiones, réplicas, configs y consumer groups). Se valida al arrancar: un valor imposible
 * (0 particiones, DLT con menos particiones que el original...) detiene la app con un mensaje claro en vez
 * de fallar después, en medio de una venta.
 *
 * @param enabled   interruptor general del streaming
 * @param topics    tópicos del sistema y sus DLT
 * @param consumers consumer groups de esta API
 * @param retry     reintentos ante errores transitorios
 * @param metrics   parámetros del cálculo de métricas en memoria
 * @param demo      opciones de la demo (simulación de ventas y fallos)
 */
@Validated
@ConfigurationProperties(prefix = "app.streaming")
public record StreamingProperties(
        boolean enabled,
        @NotNull @Valid Topics topics,
        @NotNull @Valid Consumers consumers,
        @NotNull @Valid Retry retry,
        @NotNull @Valid Metrics metrics,
        @NotNull @Valid Demo demo) {

    /** Un tópico: nombre, particiones, factor de replicación y configs propias (retention.ms, cleanup.policy...). */
    public record TopicProperties(
            @NotBlank String name,
            @Min(1) int partitions,
            @Min(1) @Max(3) short replicas,
            Map<String, String> configs) {

        public TopicProperties {
            configs = configs == null ? Map.of() : Map.copyOf(configs);
        }
    }

    /** Configuración común a los tópicos .DLT (el nombre sale de tópico original + sufijo). */
    public record DeadLetterProperties(
            @NotBlank String suffix,
            @Min(1) int partitions,
            @Min(1) @Max(3) short replicas,
            Map<String, String> configs) {

        public DeadLetterProperties {
            configs = configs == null ? Map.of() : Map.copyOf(configs);
        }
    }

    public record Topics(
            @NotNull @Valid TopicProperties ventas,
            @NotNull @Valid TopicProperties stock,
            @NotNull @Valid DeadLetterProperties deadLetter) {

        /** Nombre del DLT de un tópico, p. ej. {@code ventas.eventos.DLT}. */
        public String deadLetterOf(String topic) {
            return topic + deadLetter.suffix();
        }

        /**
         * El recuperador publica el mensaje fallido en la MISMA partición del original: si el DLT tuviera
         * menos particiones, el envío fallaría y el mensaje no se podría apartar.
         */
        @AssertTrue(message = "los tópicos .DLT deben tener al menos tantas particiones como ventas y stock")
        public boolean isDeadLetterWideEnough() {
            return ventas == null || stock == null || deadLetter == null
                    || deadLetter.partitions() >= Math.max(ventas.partitions(), stock.partitions());
        }
    }

    /** Un consumer group de esta API y cuántos consumidores (hilos) abre. */
    public record GroupProperties(@NotBlank String group, @Min(1) @Max(10) int concurrency) {
    }

    public record Consumers(
            @NotNull @Valid GroupProperties metricasVentas,
            @NotNull @Valid GroupProperties historialVentas,
            @NotNull @Valid GroupProperties monitorStock,
            @NotNull @Valid GroupProperties monitorDlt) {
    }

    /**
     * @param attempts  reintentos tras el primer intento fallido (2 = 3 intentos en total)
     * @param backoffMs espera fija entre intentos
     */
    public record Retry(@PositiveOrZero @Max(10) int attempts, @PositiveOrZero long backoffMs) {
    }

    /**
     * @param windowSeconds ventana de "ventas del último minuto"
     * @param topSize       largo del ranking de productos
     * @param recentEvents  cuántos eventos recientes se muestran
     */
    public record Metrics(@Min(1) int windowSeconds, @Min(1) int topSize, @Min(1) int recentEvents) {
    }

    /**
     * @param simulatedFailuresEnabled permite simular un fallo transitorio del consumidor (reintentos + DLT)
     * @param maxSimulatedSales        tope de ventas sintéticas por llamada a /streaming/simulate
     */
    public record Demo(boolean simulatedFailuresEnabled, @Min(1) int maxSimulatedSales) {
    }
}
