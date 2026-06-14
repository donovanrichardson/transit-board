package dev.shinpei.transitboard.api;

import dev.shinpei.transitboard.model.ObaResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SiblingResolverTest {

    @Test
    void resolvesSiblings() {
        // Parent has children A, B, C. Current stop is B → siblings are [A, C]
        ObaResponse.Stop parent = new ObaResponse.Stop();
        parent.id = "PARENT";
        parent.name = "Main Station";

        ObaResponse.Stop stopA = new ObaResponse.Stop();
        stopA.id = "A";
        stopA.parent = "PARENT";

        ObaResponse.Stop stopB = new ObaResponse.Stop();
        stopB.id = "B";
        stopB.parent = "PARENT";

        ObaResponse.Stop stopC = new ObaResponse.Stop();
        stopC.id = "C";
        stopC.parent = "PARENT";

        List<ObaResponse.Stop> allStops = List.of(parent, stopA, stopB, stopC);

        List<String> siblings = SiblingResolver.resolve("B", "PARENT", allStops);

        assertEquals(2, siblings.size());
        assertTrue(siblings.contains("A"));
        assertTrue(siblings.contains("C"));
        assertFalse(siblings.contains("B"));
    }

    @Test
    void noParent() {
        // No parent → empty siblings
        List<String> siblings = SiblingResolver.resolve("B", null, List.of());
        assertTrue(siblings.isEmpty());
    }
}
