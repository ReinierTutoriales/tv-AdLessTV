package com.adlesstv.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

public class NaturalOrderTest {
    private final Collator collator = Collator.getInstance(Locale.ROOT);

    @Test public void numberedInputsKeepPhysicalOrder() {
        List<String> labels = new ArrayList<>(Arrays.asList("HDMI 10", "HDMI 2", "AV", "HDMI 1", "HDMI 3"));
        labels.sort((a, b) -> NaturalOrder.compare(a, b, collator));
        assertEquals(Arrays.asList("AV", "HDMI 1", "HDMI 2", "HDMI 3", "HDMI 10"), labels);
    }

    @Test public void longNumbersAndLeadingZerosDoNotOverflowOrTie() {
        assertTrue(NaturalOrder.compare("Input 99999999999999999999", "Input 100000000000000000000", collator) < 0);
        assertTrue(NaturalOrder.compare("HDMI 01", "HDMI 1", collator) > 0);
        assertTrue(NaturalOrder.compare("HDMI", "HDMI 1", collator) < 0);
        assertEquals(0, NaturalOrder.compare("HDMI 2", "HDMI 2", collator));
    }
}
