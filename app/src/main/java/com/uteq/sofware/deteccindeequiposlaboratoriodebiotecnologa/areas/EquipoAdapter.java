package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.areas;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.model.Equipo;

public class EquipoAdapter extends RecyclerView.Adapter<EquipoAdapter.EquipoViewHolder> {

    public interface OnEquipoClickListener {
        void onEquipoClick(Equipo equipo);
    }

    private final List<Equipo> equipos;
    private final OnEquipoClickListener listener;

    public EquipoAdapter(List<Equipo> equipos, OnEquipoClickListener listener) {
        this.equipos = equipos;
        this.listener = listener;
    }

    @NonNull
    @Override
    public EquipoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_equipo, parent, false);
        return new EquipoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EquipoViewHolder holder, int position) {
        Equipo equipo = equipos.get(position);
        holder.nombre.setText(equipo.getNombre());
        holder.descripcion.setText(equipo.getDescripcionBreve());
        holder.chipDemo.setVisibility(equipo.esDatoDemo() ? View.VISIBLE : View.GONE);
        holder.itemView.setOnClickListener(v -> listener.onEquipoClick(equipo));
    }

    @Override
    public int getItemCount() {
        return equipos.size();
    }

    static class EquipoViewHolder extends RecyclerView.ViewHolder {
        final TextView nombre;
        final TextView descripcion;
        final TextView chipDemo;

        EquipoViewHolder(@NonNull View itemView) {
            super(itemView);
            nombre = itemView.findViewById(R.id.textNombreEquipo);
            descripcion = itemView.findViewById(R.id.textDescripcionEquipo);
            chipDemo = itemView.findViewById(R.id.chipDatoDemo);
        }
    }
}
