package dev.mekkaoui.shortener.code;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62Test {

    private static final long M = Base62.DOMAIN_M;

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 61L, 62L, 3521614606207L})
    void shouldRoundTripBoundaryValuesAtLengthSeven(long value) {
        String encoded = Base62.encode(value, 7);
        assertThat(encoded)
                .hasSize(7)
                .matches("^[0-9A-Za-z]{7}$");
        long decoded = Base62.decode(encoded);
        assertThat(decoded).isEqualTo(value);
    }

    @Test
    void shouldRoundTripRandomValuesAtLengthSeven() {
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            long value = Math.abs(random.nextLong()) % M;
            String encoded = Base62.encode(value, 7);
            assertThat(encoded)
                    .hasSize(7)
                    .matches("^[0-9A-Za-z]{7}$");
            assertThat(Base62.decode(encoded)).isEqualTo(value);
        }
    }

    @Test
    void shouldPadWithLeadingZerosToFixedLength() {
        assertThat(Base62.encode(0L, 7)).isEqualTo("0000000");
        assertThat(Base62.encode(1L, 7)).isEqualTo("0000001");
        assertThat(Base62.encode(61L, 7)).isEqualTo("000000z");
        assertThat(Base62.encode(62L, 7)).isEqualTo("0000010");
        assertThat(Base62.encode(M - 1, 7)).isEqualTo("zzzzzzz");
    }

    @Test
    void shouldRejectNegativeValue() {
        assertThatThrownBy(() -> Base62.encode(-1L, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be non-negative");
    }

    @Test
    void shouldRejectValueAtOrAboveCapacity() {
        assertThatThrownBy(() -> Base62.encode(M, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds capacity");

        assertThatThrownBy(() -> Base62.encode(M + 1, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds capacity");
    }

    @Test
    void shouldRejectInvalidDecodeCharacters() {
        assertThatThrownBy(() -> Base62.decode("abc-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Base62 character");

        assertThatThrownBy(() -> Base62.decode("abc 123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Base62 character");
    }

    @Test
    void shouldRejectNullOrEmptyDecodeInput() {
        assertThatThrownBy(() -> Base62.decode(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Base62.decode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
