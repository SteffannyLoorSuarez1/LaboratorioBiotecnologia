package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.AreaLaboratorio;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.Equipo;

/**
 * Fuente de datos en memoria para las áreas y equipos del laboratorio.
 * <p>
 * Las 3 áreas son definitivas. Los equipos son los 7 equipos REALES del laboratorio (no datos
 * de demostración): sus {@code claseDetector} coinciden exactamente, en nombre, con los
 * {@code name_internal} de {@code ml/classes.json} (fuente de verdad del pipeline YOLO11n) y
 * con el orden usado en {@code labels.txt}. Sus fichas técnicas (función, EPP, riesgos, etc.)
 * todavía no están documentadas ({@code tieneDocumentacion = false}) y se muestran como
 * "Información pendiente" en la interfaz hasta que se carguen datos reales; no se inventan.
 * <p>
 * Esta clase es también la fuente única de verdad del mapeo "clase YOLO ↔ Equipo ↔ Área" usado
 * por la futura integración de {@code YoloTfliteDetector} (ver {@link #obtenerEquipoPorClaseDetector}
 * y {@code docs/YOLO_SETUP.md}).
 */
public final class LabRepository {

    public static final String AREA_CULTIVO_TEJIDOS = "area_cultivo_tejidos";
    public static final String AREA_MICROBIOLOGIA = "area_microbiologia";
    public static final String AREA_BIOLOGIA_MOLECULAR = "area_biologia_molecular";

    private static LabRepository instancia;

    private final List<AreaLaboratorio> areas;
    private final List<Equipo> equipos;
    /** Cache O(1) de {@code claseDetector -> Equipo}, construida una sola vez. OverlayView llama
     * a {@link #obtenerNombreCorto}/{@link #obtenerNombreAmigable} una vez POR DETECCIÓN EN CADA
     * FOTOGRAMA (potencialmente varias detecciones simultáneas, ~30 veces por segundo): resolver
     * eso con un escaneo lineal de la lista de equipos en cada llamada es innecesario cuando un
     * mapa lo resuelve en tiempo constante sin afectar la fluidez de cámara + inferencia. */
    private final Map<String, Equipo> equiposPorClaseDetector;

    private LabRepository() {
        areas = crearAreas();
        equipos = crearEquiposReales();
        equiposPorClaseDetector = new HashMap<>();
        for (Equipo equipo : equipos) {
            equiposPorClaseDetector.put(equipo.getClaseDetector(), equipo);
        }
    }

    public static synchronized LabRepository getInstancia() {
        if (instancia == null) {
            instancia = new LabRepository();
        }
        return instancia;
    }

    public List<AreaLaboratorio> obtenerAreas() {
        return Collections.unmodifiableList(areas);
    }

    public AreaLaboratorio obtenerAreaPorId(String areaId) {
        for (AreaLaboratorio area : areas) {
            if (area.getId().equals(areaId)) {
                return area;
            }
        }
        return null;
    }

    public List<Equipo> obtenerEquiposPorArea(String areaId) {
        List<Equipo> resultado = new ArrayList<>();
        for (Equipo equipo : equipos) {
            if (equipo.getAreaId().equals(areaId)) {
                resultado.add(equipo);
            }
        }
        return resultado;
    }

    public Equipo obtenerEquipoPorId(String equipoId) {
        for (Equipo equipo : equipos) {
            if (equipo.getId().equals(equipoId)) {
                return equipo;
            }
        }
        return null;
    }

    /**
     * Busca el {@link Equipo} cuyo {@code claseDetector} coincide con el nombre de clase
     * devuelto por el detector YOLO (por ejemplo {@code DetectionResult.getClassName()}).
     * Es el punto de entrada del mapeo "clase YOLO → Equipo" descrito en
     * {@code docs/YOLO_SETUP.md}; devuelve {@code null} si la clase no corresponde a ningún
     * equipo conocido (por ejemplo, una clase agregada al modelo pero aún no dada de alta aquí).
     */
    public Equipo obtenerEquipoPorClaseDetector(String claseDetector) {
        return claseDetector == null ? null : equiposPorClaseDetector.get(claseDetector);
    }

    /**
     * Nombre amigable para mostrar en pantalla (overlay de detección, selección desde cámara)
     * a partir de la clase interna que devuelve el detector (igual a {@code labels.txt}, p. ej.
     * {@code electroforesis_owl_easycast}). Si la clase todavía no está dada de alta aquí (por
     * ejemplo una clase nueva del futuro modelo de 16 clases sin equipo registrado todavía), se
     * devuelve la clase interna tal cual en vez de fallar o mostrar un texto vacío.
     */
    public String obtenerNombreAmigable(String claseDetector) {
        Equipo equipo = obtenerEquipoPorClaseDetector(claseDetector);
        return equipo != null ? equipo.getNombre() : claseDetector;
    }

    /**
     * Nombre corto para la etiqueta del bounding box en la cámara (ver {@code OverlayView}):
     * usa {@code Equipo.getNombreCorto()} si el equipo tiene uno curado; si no, cae de vuelta al
     * nombre completo ({@link #obtenerNombreAmigable}). El nombre completo sigue siendo el que
     * se usa en el chat, la ficha técnica y el contexto enviado al backend — esto SOLO acorta lo
     * que se dibuja sobre la vista previa, no cambia ningún identificador de clase.
     */
    public String obtenerNombreCorto(String claseDetector) {
        Equipo equipo = obtenerEquipoPorClaseDetector(claseDetector);
        if (equipo == null) {
            return claseDetector;
        }
        String corto = equipo.getNombreCorto();
        return (corto != null && !corto.isEmpty()) ? corto : equipo.getNombre();
    }

    private List<AreaLaboratorio> crearAreas() {
        List<AreaLaboratorio> lista = new ArrayList<>();
        lista.add(new AreaLaboratorio(
                AREA_CULTIVO_TEJIDOS,
                "Área de cultivo de tejidos vegetales",
                "Propagación y manejo in vitro de material vegetal en condiciones asépticas."));
        lista.add(new AreaLaboratorio(
                AREA_MICROBIOLOGIA,
                "Área de microbiología",
                "Aislamiento, cultivo y análisis de microorganismos."));
        lista.add(new AreaLaboratorio(
                AREA_BIOLOGIA_MOLECULAR,
                "Área de biología molecular",
                "Extracción, amplificación y análisis de ácidos nucleicos."));
        return lista;
    }

    /**
     * Los 7 equipos REALES del laboratorio, correspondientes 1:1 a las 7 clases de
     * {@code ml/classes.json} (mismo {@code class_id} implícito en el orden de esa lista, mismo
     * {@code name_internal} usado aquí como {@code claseDetector}). No son datos de demostración
     * ({@code esDatoDemo = false}); lo que falta es su ficha técnica documental
     * ({@code tieneDocumentacion = false}), que se completará con información real del
     * laboratorio y no se inventa aquí.
     */
    private List<Equipo> crearEquiposReales() {
        String pendiente = "Información pendiente de documentación del laboratorio.";
        List<Equipo> lista = new ArrayList<>();

        // Área: cultivo de tejidos vegetales (class_id 0-3)
        Equipo bodSensor = new Equipo(
                "bod_sensor", "BOD Sensor", "bod_sensor",
                AREA_CULTIVO_TEJIDOS, pendiente, false, false);
        lista.add(bodSensor);

        Equipo luxFcMeter = new Equipo(
                "lux_fc_meter", "Broad Range LUX/FC Meter", "lux_fc_meter",
                AREA_CULTIVO_TEJIDOS, pendiente, false, false);
        luxFcMeter.setNombreCorto("LUX/FC Meter");
        lista.add(luxFcMeter);

        Equipo medidorMultiparametro = new Equipo(
                "medidor_multiparametro", "Medidor multiparámetro electroquímico de mesa", "medidor_multiparametro",
                AREA_CULTIVO_TEJIDOS, pendiente, false, false);
        medidorMultiparametro.setNombreCorto("Medidor multiparámetro");
        lista.add(medidorMultiparametro);

        Equipo electroforesis = new Equipo(
                "electroforesis_owl_easycast",
                "Sistema de electroforesis horizontal Thermo Scientific Owl EasyCast B1-BP",
                "electroforesis_owl_easycast",
                AREA_CULTIVO_TEJIDOS, pendiente, false, false);
        electroforesis.setNombreCorto("Electroforesis Owl EasyCast");
        lista.add(electroforesis);

        // Área: microbiología (class_id 4-6)
        lista.add(new Equipo(
                "armario_calefactor_ule600", "Armario calefactor ULE 600", "armario_calefactor_ule600",
                AREA_MICROBIOLOGIA, pendiente, false, false));
        lista.add(new Equipo(
                "horno_secado", "Horno de secado", "horno_secado",
                AREA_MICROBIOLOGIA, pendiente, false, false));
        lista.add(new Equipo(
                "uv_pcr_workstation", "UV PCR Workstation", "uv_pcr_workstation",
                AREA_MICROBIOLOGIA, pendiente, false, false));

        // Área: biología molecular — sin equipos todavía (sin fotografías ni clases definidas).

        return lista;
    }
}
