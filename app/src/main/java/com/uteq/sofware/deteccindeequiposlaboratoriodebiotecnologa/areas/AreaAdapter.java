package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.areas;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.AreaLaboratorio;

public class AreaAdapter extends RecyclerView.Adapter<AreaAdapter.AreaViewHolder> {

    public interface OnAreaClickListener {
        void onAreaClick(AreaLaboratorio area);
    }

    private final List<AreaLaboratorio> areas;
    private final OnAreaClickListener listener;

    public AreaAdapter(List<AreaLaboratorio> areas, OnAreaClickListener listener) {
        this.areas = areas;
        this.listener = listener;
    }

    @NonNull
    @Override
    public AreaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_area, parent, false);
        return new AreaViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AreaViewHolder holder, int position) {
        AreaLaboratorio area = areas.get(position);
        holder.nombre.setText(area.getNombre());
        holder.descripcion.setText(area.getDescripcion());
        holder.itemView.setOnClickListener(v -> listener.onAreaClick(area));
    }

    @Override
    public int getItemCount() {
        return areas.size();
    }

    static class AreaViewHolder extends RecyclerView.ViewHolder {
        final TextView nombre;
        final TextView descripcion;

        AreaViewHolder(@NonNull View itemView) {
            super(itemView);
            nombre = itemView.findViewById(R.id.textNombreArea);
            descripcion = itemView.findViewById(R.id.textDescripcionArea);
        }
    }
}
