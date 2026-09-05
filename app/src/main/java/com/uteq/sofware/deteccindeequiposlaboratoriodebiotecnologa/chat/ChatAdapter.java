package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.R;
import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network.Fuente;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnEscucharClickListener {
        void onEscuchar(String texto);
    }

    private static final int TIPO_USUARIO = 1;
    private static final int TIPO_ASISTENTE = 2;

    private final List<ChatMensaje> mensajes = new ArrayList<>();
    private final OnEscucharClickListener escucharClickListener;

    public ChatAdapter(OnEscucharClickListener escucharClickListener) {
        this.escucharClickListener = escucharClickListener;
    }

    public void agregarMensaje(ChatMensaje mensaje) {
        mensajes.add(mensaje);
        notifyItemInserted(mensajes.size() - 1);
    }

    public int getCantidadMensajes() {
        return mensajes.size();
    }

    /** Historial actual, para persistirlo en {@code ChatActivity.onSaveInstanceState} (ver
     * {@link #restaurarMensajes}). */
    public List<ChatMensaje> getMensajes() {
        return new ArrayList<>(mensajes);
    }

    /** Reemplaza el historial completo (usado al recrear {@code ChatActivity} desde un
     * {@code Bundle} guardado) y refresca la lista. */
    public void restaurarMensajes(List<ChatMensaje> mensajesGuardados) {
        mensajes.clear();
        if (mensajesGuardados != null) {
            mensajes.addAll(mensajesGuardados);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return mensajes.get(position).esUsuario() ? TIPO_USUARIO : TIPO_ASISTENTE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TIPO_USUARIO) {
            return new UsuarioViewHolder(inflater.inflate(R.layout.item_chat_usuario, parent, false));
        }
        return new AsistenteViewHolder(inflater.inflate(R.layout.item_chat_asistente, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMensaje mensaje = mensajes.get(position);
        if (holder instanceof UsuarioViewHolder) {
            ((UsuarioViewHolder) holder).texto.setText(mensaje.getTexto());
        } else if (holder instanceof AsistenteViewHolder) {
            AsistenteViewHolder asistenteHolder = (AsistenteViewHolder) holder;
            asistenteHolder.texto.setText(MarkdownFormatter.toDisplaySpannable(mensaje.getTexto()));

            List<Fuente> fuentes = mensaje.getFuentes();
            if (fuentes.isEmpty()) {
                asistenteHolder.contenedorFuentes.setVisibility(View.GONE);
            } else {
                StringBuilder builder = new StringBuilder();
                for (Fuente fuente : fuentes) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append("• ").append(fuente.getArchivo());
                    if (fuente.getReferencia() != null && !fuente.getReferencia().isEmpty()) {
                        builder.append(" — ").append(fuente.getReferencia());
                    }
                }
                asistenteHolder.textFuentes.setText(builder.toString());
                asistenteHolder.contenedorFuentes.setVisibility(View.VISIBLE);
            }

            asistenteHolder.botonEscuchar.setOnClickListener(v -> {
                if (escucharClickListener != null) {
                    escucharClickListener.onEscuchar(mensaje.getTexto());
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return mensajes.size();
    }

    static class UsuarioViewHolder extends RecyclerView.ViewHolder {
        final TextView texto;

        UsuarioViewHolder(@NonNull View itemView) {
            super(itemView);
            texto = itemView.findViewById(R.id.textMensajeUsuario);
        }
    }

    static class AsistenteViewHolder extends RecyclerView.ViewHolder {
        final TextView texto;
        final View contenedorFuentes;
        final TextView textFuentes;
        final ImageButton botonEscuchar;

        AsistenteViewHolder(@NonNull View itemView) {
            super(itemView);
            texto = itemView.findViewById(R.id.textMensajeAsistente);
            contenedorFuentes = itemView.findViewById(R.id.contenedorFuentes);
            textFuentes = itemView.findViewById(R.id.textFuentesAsistente);
            botonEscuchar = itemView.findViewById(R.id.botonEscuchar);
        }
    }
}
