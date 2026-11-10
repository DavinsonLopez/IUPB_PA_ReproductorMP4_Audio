package servicios;

import java.lang.management.ManagementFactory;
import java.nio.Buffer; // Importante para las muestras de audio en JavaCV
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import com.sun.management.OperatingSystemMXBean;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

public class ServicioDecodificador {

    public static final int TAMAÑO_MAXIMO_COLA = 30;
    private final OperatingSystemMXBean beanSO = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private volatile boolean ejecutando = false;
    private volatile boolean detenido = false;
    private volatile boolean decodificando = false;

    public record ContenedorFrame(Frame frame, long tiempo) {}

    // NUEVO: Contenedor para las muestras de audio
    public record ContenedorAudio(Buffer[] samples, int numChannels, int sampleRate) {}

    public record Metricas(long memoriaHeapMB, long memoriaOffHeapMB,
                           double porcentajeUsoCPU, int ocupacionCola,
                           long tiempoLectura) {
    }

    private final BlockingQueue<ContenedorFrame> colaVideo = new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    // NUEVO: Cola independiente para el flujo de audio
    private final BlockingQueue<ContenedorAudio> colaAudio = new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    // Cola intermedia entre el procesamiento DSP y la salida física de audio.
    // La cola limita la memoria utilizada y proporciona la sincronización
    // necesaria entre ambos hilos (put/take tienen relación happens-before).
    private final BlockingQueue<ContenedorAudio> colaAudioProcesado =
            new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    public boolean isEjecutando() {
        return ejecutando;
    }

    /**
     * Deja el servicio listo antes de lanzar los hilos consumidores. Así el
     * DSP no puede observar {@code ejecutando=false} y terminar antes de que
     * el productor empiece a decodificar.
     */
    public synchronized boolean prepararReproduccion() {
        if (ejecutando) {
            return false;
        }

        colaVideo.clear();
        colaAudio.clear();
        colaAudioProcesado.clear();
        detenido = false;
        decodificando = false;
        ejecutando = true;
        return true;
    }

    public void iniciarDecodificacion(String rutaVideo) {
        synchronized (this) {
            if (decodificando) return;
            if (!ejecutando && !prepararReproduccion()) return;
            decodificando = true;
        }

        try (var grabber = new FFmpegFrameGrabber(rutaVideo)) { // Usando tu instancia de FFmpegFrameGrabber

            grabber.start();

            Frame frameActual;
            while (ejecutando && (frameActual = grabber.grab()) != null) {

                if (frameActual.image != null) {
                    long tiempoInicial = System.nanoTime();
                    Frame frameClonado = frameActual.clone();
                    long tiempo = (System.nanoTime() - tiempoInicial) / 1000000;

                    // El audio tiene prioridad: si el renderizado de video va
                    // retrasado, se descarta el frame más reciente en lugar de
                    // bloquear al productor y dejar sin datos al DSP de audio.
                    // Las colas de audio sí aplican backpressure.
                    if (!colaVideo.offer(new ContenedorFrame(frameClonado, tiempo))) {
                        frameClonado.close();
                    }
                }

                if (frameActual.samples != null) {
                    Buffer[] samplesClonados = new Buffer[frameActual.samples.length];
                    for (int i = 0; i < frameActual.samples.length; i++) {
                        samplesClonados[i] = Util.duplicarBuffer(frameActual.samples[i]);
                    }

                    if (!encolar(colaAudio, new ContenedorAudio(
                            samplesClonados,
                            frameActual.audioChannels,
                            frameActual.sampleRate
                    ))) {
                        break;
                    }
                }
             }
        } catch (Exception ex) {
            System.err.println("Error en el SERVICIO de Decodificación A/V: " + ex.getMessage());
        } finally {
            synchronized (this) {
                decodificando = false;
                ejecutando = false;
            }
        }
    }

    /**
     * Inserta en una cola bloqueante sin quedar atrapado para siempre si la
     * reproducción se detiene. La espera máxima es corta para poder observar
     * el cambio de {@code ejecutando}.
     */
    private <T> boolean encolar(BlockingQueue<T> cola, T elemento) throws InterruptedException {
        while (ejecutando) {
            if (cola.offer(elemento, 50, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    public ContenedorFrame getSiguienteFrame() throws InterruptedException {
        return colaVideo.poll();
    }

    public ContenedorAudio getSiguienteAudio() throws InterruptedException {
        return colaAudio.poll(50, TimeUnit.MILLISECONDS);
    }

    public ContenedorAudio getSiguienteAudioProcesado() throws InterruptedException {
        return colaAudioProcesado.poll(50, TimeUnit.MILLISECONDS);
    }

    public boolean publicarAudioProcesado(ContenedorAudio audio) throws InterruptedException {
        // El fin normal de la decodificación pone ejecutando=false, pero aún
        // puede quedar audio pendiente. Solo una detención explícita debe
        // descartar bloques.
        while (!detenido) {
            if (colaAudioProcesado.offer(audio, 50, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    public boolean hayVideoPendiente() {
        return !colaVideo.isEmpty();
    }

    public boolean hayAudioPorProcesar() {
        return !colaAudio.isEmpty();
    }

    public boolean hayAudioPendiente() {
        return !colaAudio.isEmpty() || !colaAudioProcesado.isEmpty();
    }

    public synchronized void detener() {
        detenido = true;
        ejecutando = false;
        decodificando = false;
        this.colaVideo.clear();
        this.colaAudio.clear(); // Limpiar también la cola de audio
        this.colaAudioProcesado.clear();
    }

    public Metricas getMetricas(long tiempoLectura) {
        Runtime runtime = Runtime.getRuntime();
        long mb = 1048576;

        long memoriaHeapMB = (runtime.totalMemory() - runtime.freeMemory()) / mb;
        long memoriaProceso = beanSO.getCommittedVirtualMemorySize() / mb;
        long memoriaOffHeapMB = Math.max(0, memoriaProceso - memoriaHeapMB);
        double porcentajeUsoCPU = beanSO.getCpuLoad() * 100;

        // Se reporta el tamaño combinado o el de video para métricas
        return new Metricas(memoriaHeapMB, memoriaOffHeapMB, porcentajeUsoCPU, colaVideo.size(), tiempoLectura);
    }
}
