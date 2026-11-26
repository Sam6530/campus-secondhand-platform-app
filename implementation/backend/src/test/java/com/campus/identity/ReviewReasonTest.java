package com.campus.identity;

import com.campus.shared.api.ApiException;
import com.campus.shared.text.ReviewReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ReviewReasonTest {
    @ParameterizedTest
    @ValueSource(ints={1000,1001,1500})
    void acceptsContractBoundaries(int count) {
        String input="审".repeat(count);
        assertEquals(input,ReviewReason.require(input));
    }

    @Test void countsUnicodeCodePointsAndRejectsOversizeOrBlank() {
        assertEquals("😀".repeat(1500),ReviewReason.require("😀".repeat(1500)));
        assertThrows(ApiException.class,()->ReviewReason.require("审".repeat(1501)));
        assertThrows(ApiException.class,()->ReviewReason.require(" "));
    }
}
