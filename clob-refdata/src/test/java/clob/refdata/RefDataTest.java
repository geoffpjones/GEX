package clob.refdata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RefDataTest {
    @Test
    void userCanBeMappedToMultipleAccounts() {
        RefData r = RefData.builder().entitle(1, 10).entitle(1, 11).entitle(2, 10).build();
        assertTrue(r.isEntitled(1, 10));
        assertTrue(r.isEntitled(1, 11));
        assertTrue(r.isEntitled(2, 10));
        assertFalse(r.isEntitled(2, 11));
    }

    @Test
    void instrumentLookupByIdAndKey() {
        Instrument i = Instrument.builder(3, "EUR/USD", "SPOT").build();
        RefData r = RefData.builder().add(i).build();
        assertSame(i, r.instrument(3));
        assertSame(i, r.instrument("EUR/USD", "SPOT"));
        assertSame(null, r.instrument(2));
    }

    @Test
    void rejectsDuplicateInstruments() {
        assertThrows(IllegalArgumentException.class, () -> RefData.builder()
                .add(Instrument.builder(0, "EUR/USD", "SPOT").build())
                .add(Instrument.builder(1, "EUR/USD", "SPOT").build()));
    }
}
