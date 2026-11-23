package com.charmeetchic.api.messaging.config;

import com.charmeetchic.api.messaging.config.MessagingProperties.QueueProperties;
import com.charmeetchic.api.messaging.config.MessagingProperties.SubscriberQueueProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Reglas cruzadas de las propiedades de colas (las que no se pueden expresar con una sola anotación). */
class MessagingPropertiesRulesTest {

    @Test
    void publishKeyUsaLaRoutingKeySiNoHayUnaConcreta() {
        assertThat(new QueueProperties("q", "rk", null, "q.dlq", 10, null).publishKey()).isEqualTo("rk");
        assertThat(new QueueProperties("q", "rk", "  ", "q.dlq", 10, null).publishKey()).isEqualTo("rk");
        assertThat(new QueueProperties("q", "a.*", "a.b", "q.dlq", 10, null).publishKey()).isEqualTo("a.b");
    }

    @Test
    void laDlqDebeLlamarseDistintoQueLaCola() {
        assertThat(new QueueProperties("q", "rk", null, "q.dlq", 10, null).isDeadLetterQueueDistinct()).isTrue();
        assertThat(new QueueProperties("q", "rk", null, "q", 10, null).isDeadLetterQueueDistinct()).isFalse();
        assertThat(new QueueProperties(null, "rk", null, "q", 10, null).isDeadLetterQueueDistinct()).isTrue();
        assertThat(new QueueProperties("q", "rk", null, null, 10, null).isDeadLetterQueueDistinct()).isTrue();

        assertThat(new SubscriberQueueProperties("s", "s.dlq", 10, null).isDeadLetterQueueDistinct()).isTrue();
        assertThat(new SubscriberQueueProperties("s", "s", 10, null).isDeadLetterQueueDistinct()).isFalse();
        assertThat(new SubscriberQueueProperties(null, "s", 10, null).isDeadLetterQueueDistinct()).isTrue();
        assertThat(new SubscriberQueueProperties("s", null, 10, null).isDeadLetterQueueDistinct()).isTrue();
    }

    @Test
    void laKeyDePublicacionNoPuedeTenerComodines() {
        assertThat(new QueueProperties("q", "a.b", null, "q.dlq", 10, null).isPublishKeyConcrete()).isTrue();
        assertThat(new QueueProperties("q", "a.*", null, "q.dlq", 10, null).isPublishKeyConcrete()).isFalse();
        assertThat(new QueueProperties("q", "a.#", null, "q.dlq", 10, null).isPublishKeyConcrete()).isFalse();
        assertThat(new QueueProperties("q", null, null, "q.dlq", 10, null).isPublishKeyConcrete()).isTrue();
    }
}
