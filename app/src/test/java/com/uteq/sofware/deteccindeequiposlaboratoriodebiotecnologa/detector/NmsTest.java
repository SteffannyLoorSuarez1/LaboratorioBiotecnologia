package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.detector;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.DetectionResult;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class NmsTest {
    @Test public void conservaLaMayorConfianzaEnCajasDuplicadas() {
        DetectionResult completa = new DetectionResult(1, "equipo", .95f, 0, 0, 100, 100);
        DetectionResult menor = new DetectionResult(1, "equipo", .6f, 5, 5, 95, 95);
        List<DetectionResult> result = YoloTfliteDetector.nmsPorClase(Arrays.asList(menor, completa));
        assertEquals(1, result.size());
        assertSame(completa, result.get(0));
    }
    @Test public void noEliminaEquiposSeparadosNiOtraClase() {
        DetectionResult a = new DetectionResult(1, "a", .95f, 0, 0, 100, 100);
        DetectionResult b = new DetectionResult(1, "a", .9f, 200, 0, 300, 100);
        DetectionResult c = new DetectionResult(2, "b", .8f, 0, 0, 100, 100);
        assertEquals(3, YoloTfliteDetector.nmsPorClase(Arrays.asList(a, b, c)).size());
    }
}
