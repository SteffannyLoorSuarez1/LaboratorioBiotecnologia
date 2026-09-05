package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model;

import java.util.Collections;
import java.util.List;

/**
 * Representa un equipo del laboratorio que podrá ser reconocido por el detector YOLO.
 * <p>
 * {@code claseDetector} es el nombre de clase que el modelo .tflite devolverá en el futuro
 * (labels.txt). Mientras el dataset y el entrenamiento no estén definidos, este campo es
 * únicamente informativo y no se usa para inferencia real.
 */
public class Equipo {

    private final String id;
    private final String nombre;
    private final String claseDetector;
    private final String areaId;
    private final String descripcionBreve;
    private final boolean tieneDocumentacion;
    private final boolean esDatoDemo;

    /** Nombre corto opcional para la etiqueta del bounding box en la cámara (ver
     * {@code OverlayView}): algunos equipos tienen nombres oficiales largos (p. ej. "Sistema de
     * electroforesis horizontal Thermo Scientific Owl EasyCast B1-BP") que no caben en una
     * etiqueta compacta sobre la vista previa. Si es {@code null}, se usa {@link #getNombre()}
     * completo (ver {@code LabRepository.obtenerNombreCorto}). No afecta {@code claseDetector}
     * ni ningún identificador usado por el modelo. */
    private String nombreCorto;

    private String funcion;
    private List<String> componentesPrincipales = Collections.emptyList();
    private String procedimientoBasico;
    private List<String> epp = Collections.emptyList();
    private List<String> riesgos = Collections.emptyList();
    private List<String> practicasAcademicas = Collections.emptyList();

    public Equipo(String id, String nombre, String claseDetector, String areaId,
                  String descripcionBreve, boolean tieneDocumentacion, boolean esDatoDemo) {
        this.id = id;
        this.nombre = nombre;
        this.claseDetector = claseDetector;
        this.areaId = areaId;
        this.descripcionBreve = descripcionBreve;
        this.tieneDocumentacion = tieneDocumentacion;
        this.esDatoDemo = esDatoDemo;
    }

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getClaseDetector() {
        return claseDetector;
    }

    public String getAreaId() {
        return areaId;
    }

    public String getDescripcionBreve() {
        return descripcionBreve;
    }

    public boolean tieneDocumentacion() {
        return tieneDocumentacion;
    }

    public boolean esDatoDemo() {
        return esDatoDemo;
    }

    public String getNombreCorto() {
        return nombreCorto;
    }

    public void setNombreCorto(String nombreCorto) {
        this.nombreCorto = nombreCorto;
    }

    public String getFuncion() {
        return funcion;
    }

    public void setFuncion(String funcion) {
        this.funcion = funcion;
    }

    public List<String> getComponentesPrincipales() {
        return componentesPrincipales;
    }

    public void setComponentesPrincipales(List<String> componentesPrincipales) {
        this.componentesPrincipales = componentesPrincipales;
    }

    public String getProcedimientoBasico() {
        return procedimientoBasico;
    }

    public void setProcedimientoBasico(String procedimientoBasico) {
        this.procedimientoBasico = procedimientoBasico;
    }

    public List<String> getEpp() {
        return epp;
    }

    public void setEpp(List<String> epp) {
        this.epp = epp;
    }

    public List<String> getRiesgos() {
        return riesgos;
    }

    public void setRiesgos(List<String> riesgos) {
        this.riesgos = riesgos;
    }

    public List<String> getPracticasAcademicas() {
        return practicasAcademicas;
    }

    public void setPracticasAcademicas(List<String> practicasAcademicas) {
        this.practicasAcademicas = practicasAcademicas;
    }
}
