package com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.network;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.uteq.sofware.deteccindeequiposlaboratoriodebiotecnologa.security.SecureConfigManager;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 * PRUEBA AISLADA (no forma parte de la suite normal ni del flujo de la app): valida si la
 * API Key guardada en ConfiguracionActivity puede consultar, vía REST directo a
 * /v1/responses + file_search, el Vector Store vs_6aa2afc8c3f88191a9c07603ba6791ea.
 * <p>
 * Requisito previo: en el MISMO dispositivo/emulador donde se ejecute este test, abrir la
 * app normal, ir a Configuración y guardar una API Key de prueba antes de correrlo (este
 * test lee la misma EncryptedSharedPreferences que usa la app, no crea una clave nueva).
 * <p>
 * Cómo ejecutar: clic derecho sobre esta clase en Android Studio > Run, o:
 *   ./gradlew connectedDebugAndroidTest --tests "*.OpenAiRestTestRepositoryTest"
 * El resultado se imprime en Logcat con el tag "OpenAiRestTest".
 */
@RunWith(AndroidJUnit4.class)
public class OpenAiRestTestRepositoryTest {

    private static final String TAG = "OpenAiRestTest";

    @Test
    public void consultarVectorStoreDePrueba() throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        SecureConfigManager secureConfigManager = new SecureConfigManager(context);
        if (!secureConfigManager.hasApiKey()) {
            fail("No hay API Key guardada en este dispositivo. Abre la app > Configuración "
                    + "y guarda una API Key de prueba antes de ejecutar este test.");
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<OpenAiRestTestRepository.Resultado> resultadoRef = new AtomicReference<>();

        OpenAiRestTestRepository.ejecutarPruebaVectorStore(context, resultado -> {
            resultadoRef.set(resultado);
            latch.countDown();
        });

        boolean completado = latch.await(90, TimeUnit.SECONDS);
        if (!completado) {
            fail("Timeout de 90s esperando respuesta de OpenAI. Revisa conexión a internet "
                    + "del dispositivo/emulador (esta prueba llama directo a api.openai.com, "
                    + "no al backend local).");
            return;
        }

        OpenAiRestTestRepository.Resultado resultado = resultadoRef.get();
        assertNotNull(resultado);

        String resumen = resultado.toResumenFinal();
        for (String linea : resumen.split("\n")) {
            Log.i(TAG, linea);
        }
    }
}
