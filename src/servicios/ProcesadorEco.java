package servicios;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Etapa DSP del pipeline de audio.
 *
 * <p>Esta clase concentra el efecto de eco y la carga artificial de CPU sin
 * modificar {@link Util}. Su diseño está pensado para ejecutarse únicamente
 * desde el hilo dedicado de DSP, nunca desde el hilo que alimenta
 * {@code SourceDataLine}.</p>
 *
 * <p>El historial es independiente por canal, de modo que el eco del canal
 * izquierdo no se mezcla con el derecho. Se soportan bloques
 * {@link FloatBuffer} y {@link ShortBuffer}.</p>
 */
public class ProcesadorEco {

    private static final int RETARDO_ECO = 5;
    private static final int ITERACIONES_CARGA_CPU = 500_000;
    private static final int LOTE_CARGA_CPU = 256;
    private static final double FRACCION_TIEMPO_CARGA = 0.20; // 20 % del bloque
    private static final float SAMPLE_RATE_POR_DEFECTO = 48_000f;

    private final List<Queue<float[]>> historialesPorCanal = new ArrayList<>();

    /**
     * Limpia el historial para que una nueva reproducción no arrastre
     * muestras del archivo anterior.
     */
    public void reiniciar() {
        historialesPorCanal.clear();
    }

    /**
     * Aplica eco a todos los canales del bloque y conserva una carga
     * artificial de CPU acotada por el presupuesto temporal del bloque.
     *
     * <p>La pausa fija de 50 ms del método original es incompatible con el
     * tiempo real: un bloque de 1024 muestras a 48 kHz dura ~21.33 ms. Aquí
     * el trabajo artificial se limita al 20 % de esa duración, de modo que la
     * etapa DSP siempre pueda ir por delante de la reproducción.</p>
     */
    public void procesar(Buffer[] buffers, int canales, float sampleRate) {
        if (buffers == null || buffers.length == 0) return;

        long inicio = System.nanoTime();
        int muestrasPorCanal = calcularMuestrasPorCanal(buffers, canales);
        float frecuencia = (sampleRate > 0) ? sampleRate : SAMPLE_RATE_POR_DEFECTO;
        double duracionBloqueMs = muestrasPorCanal * 1000.0 / frecuencia;

        for (int canal = 0; canal < buffers.length; canal++) {
            procesarCanal(buffers[canal], canal);
        }

        cargarCpuArtificial(inicio, duracionBloqueMs);
    }

    private void procesarCanal(Buffer buffer, int canal) {
        if (buffer == null) return;

        while (historialesPorCanal.size() <= canal) {
            historialesPorCanal.add(new ArrayDeque<>());
        }
        Queue<float[]> historial = historialesPorCanal.get(canal);

        // 1. Si el búfer es FloatBuffer
        if (buffer instanceof FloatBuffer) {
            FloatBuffer fb = (FloatBuffer) buffer;
            fb.rewind();
            float[] muestras = new float[fb.remaining()];
            fb.get(muestras);

            aplicarEco(muestras, historial);

            fb.rewind();
            fb.put(muestras);
            fb.rewind();
        }
        // 2. Si el búfer es ShortBuffer (muy común en varios formatos MP4)
        else if (buffer instanceof ShortBuffer) {
            ShortBuffer sb = (ShortBuffer) buffer;
            sb.rewind();
            float[] muestras = new float[sb.remaining()];
            for (int i = 0; i < muestras.length; i++) {
                muestras[i] = sb.get() / 32767.0f; // Normalizar a flotante
            }

            aplicarEco(muestras, historial);

            sb.rewind();
            for (float f : muestras) {
                short s = (short) (Math.max(-1.0f, Math.min(1.0f, f)) * 32767.0f);
                sb.put(s);
            }
            sb.rewind();
        }
    }

    private void aplicarEco(float[] muestras, Queue<float[]> historial) {
        historial.add(muestras.clone());
        if (historial.size() > RETARDO_ECO) {
            float[] muestrasAntiguas = historial.poll();
            if (muestrasAntiguas != null) {
                for (int i = 0; i < muestras.length && i < muestrasAntiguas.length; i++) {
                    muestras[i] = (muestras[i] * 0.5f) + (muestrasAntiguas[i] * 0.5f);
                }
            }
        }
    }

    private int calcularMuestrasPorCanal(Buffer[] buffers, int canales) {
        int maximo = 0;
        for (Buffer buffer : buffers) {
            if (buffer != null) {
                maximo = Math.max(maximo, buffer.remaining());
            }
        }

        int canalesEfectivos = Math.max(1, canales);
        // En audio empaquetado hay un solo búfer con las muestras
        // intercaladas; en audio planar hay un búfer por canal.
        if (buffers.length < canalesEfectivos) {
            maximo /= canalesEfectivos;
        }
        return Math.max(1, maximo);
    }

    /**
     * Carga artificial de CPU acotada por el tiempo real del bloque. Si el
     * equipo es lento, se detiene antes de consumir el presupuesto de audio;
     * si es rápido, completa las iteraciones configuradas. Nunca duerme al
     * hilo que alimenta el hardware.
     */
    private void cargarCpuArtificial(long inicio, double duracionBloqueMs) {
        long presupuestoNs = (long) (duracionBloqueMs * FRACCION_TIEMPO_CARGA * 1_000_000.0);
        long limite = inicio + Math.max(0, presupuestoNs);
        double dummy = 0;

        for (int j = 0; j < ITERACIONES_CARGA_CPU; j += LOTE_CARGA_CPU) {
            for (int k = 0; k < LOTE_CARGA_CPU; k++) {
                dummy += Math.sin(j + k);
            }
            if (System.nanoTime() >= limite) {
                break;
            }
        }

        // Evita que el JIT pueda eliminar el cálculo por no usarse.
        if (Double.isNaN(dummy)) {
            System.err.print("");
        }
    }
}
