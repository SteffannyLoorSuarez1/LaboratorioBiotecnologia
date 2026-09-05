package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model;

/**
 * Representa una de las 3 áreas fijas del Laboratorio de Biotecnología de la UTEQ.
 */
public class AreaLaboratorio {

    private final String id;
    private final String nombre;
    private final String descripcion;

    public AreaLaboratorio(String id, String nombre, String descripcion) {
        this.id = id;
        this.nombre = nombre;
        this.descripcion = descripcion;
    }

    public String getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }
}
