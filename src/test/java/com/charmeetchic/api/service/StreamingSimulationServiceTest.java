package com.charmeetchic.api.service;

import com.charmeetchic.api.entity.Product;
import com.charmeetchic.api.enums.TipoDefecto;
import com.charmeetchic.api.exception.ConflictException;
import com.charmeetchic.api.exception.MessagingUnavailableException;
import com.charmeetchic.api.exception.ValidationException;
import com.charmeetchic.api.repository.ProductRepository;
import com.charmeetchic.api.streaming.event.VentaEvent;
import com.charmeetchic.api.streaming.producer.StreamingEventPublisher;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static com.charmeetchic.api.streaming.StreamingTestData.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ventas sintéticas válidas y eventos defectuosos de la demo. */
class StreamingSimulationServiceTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final ProductRepository repo = mock(ProductRepository.class);
    private final StreamingEventPublisher publisher = mock(StreamingEventPublisher.class);
    private final List<Product> catalogo = List.of(
            Product.builder().id(1L).sku("A").name("Aros").price(new BigDecimal("1000")).stock(5).active(true).build(),
            Product.builder().id(2L).sku("B").name("Collar").price(new BigDecimal("2500")).stock(5).active(true).build());

    private StreamingSimulationService service(boolean enabled, boolean failures) {
        when(publisher.isEnabled()).thenReturn(enabled);
        when(repo.findByActive(true)).thenReturn(catalogo);
        return new StreamingSimulationService(repo, publisher, props(enabled, failures, 100), new BigDecimal("0.19"),
                new Random(42));
    }

    @Test
    void publicaNVentasSinteticasValidas() {
        StreamingSimulationService.Resultado r = service(true, true).simular(10, false, TipoDefecto.VALIDACION);

        assertThat(r.ventasPublicadas()).isEqualTo(10);
        assertThat(r.tipoDefecto()).isNull();
        ArgumentCaptor<VentaEvent> captor = ArgumentCaptor.forClass(VentaEvent.class);
        verify(publisher, times(10)).publishVenta(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(v -> {
            assertThat(VALIDATOR.validate(v)).isEmpty();
            assertThat(v.origen()).isEqualTo(VentaEvent.Origen.SIMULACION);
            assertThat(v.orderId()).startsWith("SIM-");
            // total = subtotal * 1.19
            BigDecimal subtotal = v.items().stream()
                    .map(i -> i.precioUnitario().multiply(BigDecimal.valueOf(i.cantidad())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(v.total()).isEqualByComparingTo(subtotal.multiply(new BigDecimal("1.19")));
        });
    }

    @Test
    void cadaTipoDeDefectoUsaSuPublicacionDeDemo() {
        StreamingSimulationService s = service(true, true);

        s.simular(0, true, TipoDefecto.VALIDACION);
        ArgumentCaptor<VentaEvent> invalida = ArgumentCaptor.forClass(VentaEvent.class);
        verify(publisher).publishInvalidVentaForDemo(invalida.capture());
        assertThat(VALIDATOR.validate(invalida.getValue())).isNotEmpty();

        s.simular(0, true, TipoDefecto.JSON);
        verify(publisher).publishRawForDemo(startsWith("SIM-"), anyString());

        StreamingSimulationService.Resultado r = s.simular(2, true, TipoDefecto.TRANSITORIO);
        verify(publisher).publishVentaWithSimulatedFailureForDemo(any());
        assertThat(r.tipoDefecto()).isEqualTo(TipoDefecto.TRANSITORIO);
    }

    @Test
    void validaLosParametros() {
        StreamingSimulationService s = service(true, false);

        assertThatThrownBy(() -> s.simular(101, false, TipoDefecto.JSON)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> s.simular(-1, false, TipoDefecto.JSON)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> s.simular(0, false, TipoDefecto.JSON)).isInstanceOf(ValidationException.class);
        // fallos transitorios deshabilitados
        assertThatThrownBy(() -> s.simular(0, true, TipoDefecto.TRANSITORIO)).isInstanceOf(ValidationException.class);
        verify(publisher, never()).publishVenta(any());
    }

    @Test
    void sinKafkaResponde503() {
        assertThatThrownBy(() -> service(false, true).simular(1, false, TipoDefecto.JSON))
                .isInstanceOf(MessagingUnavailableException.class);
    }

    @Test
    void sinCatalogoNoHayVentasQueSimular() {
        StreamingSimulationService s = service(true, true);
        when(repo.findByActive(true)).thenReturn(List.of());

        assertThatThrownBy(() -> s.simular(1, false, TipoDefecto.JSON)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> s.simular(0, true, TipoDefecto.TRANSITORIO)).isInstanceOf(ConflictException.class);
        // un JSON roto no necesita catálogo
        s.simular(0, true, TipoDefecto.JSON);
        verify(publisher).publishRawForDemo(anyString(), anyString());
    }

    @Test
    void sincronizacionPublicaElStockDeCadaProductoActivo() {
        when(publisher.isEnabled()).thenReturn(true);
        when(repo.findByActive(true)).thenReturn(catalogo);
        StockSyncService sync = new StockSyncService(repo, publisher);

        assertThat(sync.sincronizar()).isEqualTo(2);
        verify(publisher, times(2)).publishStock(any());

        // con el streaming apagado no hace nada al arrancar
        when(publisher.isEnabled()).thenReturn(false);
        sync.onApplicationReady();
        verify(publisher, times(2)).publishStock(any());
    }
}
