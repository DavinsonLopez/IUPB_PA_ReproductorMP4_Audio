package controladores;

import org.bytedeco.javacv.Java2DFrameConverter;

import servicios.ProcesadorEco;
import servicios.ServicioDecodificador;
import servicios.TiempoServicio;
import servicios.Util;
import vistas.VistaReproductor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.image.BufferedImage;
import java.nio.Buffer;

public class ControladorReproductor {

    private final int TIEMPO_PAUSA = 200; // Muestreo 5 veces por segundo
    private final int TIEMPO_FRAME = 33; // 30 FPS -> 1000 ms/30

    // 250 ms de holgura en la línea de hardware. Esta memoria absorbe
    // pequeñas variaciones de planificación sin que el altavoz se quede sin
    // datos, pero sin añadir un retardo perceptible.
    private static final double DURACION_BUFFER_AUDIO_SEGUNDOS = 0.25;
    private static final int TAMANO_MINIMO_BUFFER_AUDIO = 4_096;
    private static final int TAMANO_MAXIMO_BUFFER_AUDIO = 1_048_576;
    private static final float SAMPLE_RATE_AUDIO_POR_DEFECTO = 48_000f;

    private final VistaReproductor vista;
    private final ServicioDecodificador servicio;
    // Etapa DSP independiente. Util.java se mantiene intacto.
    private final ProcesadorEco procesadorEco = new ProcesadorEco();
    private long ultimoTiempo;
    private volatile boolean procesamientoAudioFinalizado = true;

    public ControladorReproductor(VistaReproductor vista,
                                  ServicioDecodificador servicio) {
        this.vista = vista;
        this.servicio = servicio;

        this.vista.setSelecionarClick(evento -> iniciarProceso());
    }

    private void cicloRenderizado() {
        try (var conversor = new Java2DFrameConverter()) {
            // Drena los frames que ya estaban en la cola cuando el productor
            // terminó, para no perder el final del video.
            while (servicio.isEjecutando() || servicio.hayVideoPendiente()) {
                long inicio = System.currentTimeMillis();

                ServicioDecodificador.ContenedorFrame contenedorARenderizar = servicio.getSiguienteFrame();
                if (contenedorARenderizar != null) {
                    ultimoTiempo = contenedorARenderizar.tiempo();

                    BufferedImage imgRenderizada = conversor.convert(contenedorARenderizar.frame());
                    vista.actualizarImagenVideo(imgRenderizada);
                    contenedorARenderizar.frame().close();
                }

                long tiempoTranscurrido = System.currentTimeMillis() - inicio;
                long tiempoPausa = Math.max(0, TIEMPO_FRAME - tiempoTranscurrido);

                TiempoServicio.pausarMilisegundos(tiempoPausa);
            }
        } catch (Exception ex) {
            System.err.println("[Controlador] Error en el ciclo de renderizado: " + ex.getMessage());
        }
    }

    private void cicloTelemetria() {
        while (servicio.isEjecutando()) {
            ServicioDecodificador.Metricas metricas = servicio.getMetricas(ultimoTiempo);
            vista.actualizarTelemetria(metricas);
            TiempoServicio.pausarMilisegundos(TIEMPO_PAUSA);
        }
    }

    private void iniciarProceso() {
        var archivo = vista.solicitarArchivoVideo();
        if (archivo == null)
            return;

        // Deja el servicio en ejecución antes de lanzar a los consumidores.
        if (!servicio.prepararReproduccion()) {
            return;
        }

        // El historial del eco no debe arrastrar muestras del archivo anterior.
        procesadorEco.reiniciar();

        // 1. Hilo PRODUCTOR (decodifica video y audio concurrentemente y los encola)
        new Thread(() -> servicio.iniciarDecodificacion(archivo.getAbsolutePath()), "Hilo Decodificacion").start();

        // 2. Hilo CONSUMIDOR DE VIDEO (muestra las imágenes)
        new Thread(this::cicloRenderizado, "Hilo Renderizado").start();

        // 3. Hilo DSP: procesa el audio sin bloquear al hilo que usa el hardware
        procesamientoAudioFinalizado = false;
        new Thread(this::cicloProcesamientoAudio, "Hilo DSP Audio").start();

        // 4. Hilo CONSUMIDOR DE AUDIO (reproduce el sonido en paralelo)
        new Thread(this::cicloAudio, "Hilo Audio").start();

        // 5. Hilo TELEMETRIA
        new Thread(this::cicloTelemetria, "Hilo Telemetria").start();
    }

    private void cicloAudio() {
        SourceDataLine lineaAudio = null;
        try {
            // Este hilo únicamente consume audio ya procesado y lo entrega al
            // hardware. No debe ejecutar cálculos del efecto ni retardos.
            while (servicio.isEjecutando() || !procesamientoAudioFinalizado || servicio.hayAudioPendiente()) {
                ServicioDecodificador.ContenedorAudio audioData = servicio.getSiguienteAudioProcesado();

                if (audioData != null && audioData.samples() != null && audioData.samples().length > 0) {
                    int canales = Math.max(1, audioData.numChannels());
                    float sampleRate = audioData.sampleRate() > 0
                            ? audioData.sampleRate()
                            : SAMPLE_RATE_AUDIO_POR_DEFECTO;

                    // Configurar la línea de audio la primera vez que se reciben muestras
                    if (lineaAudio == null) {
                        AudioFormat formato = new AudioFormat(
                                AudioFormat.Encoding.PCM_SIGNED,
                                sampleRate,
                                16, // 16 bits por muestra
                                canales,
                                canales * 2, // Tamaño de frame (bytes por muestra * canales)
                                sampleRate,
                                false // Little-endian (estándar en PC/Windows/Mac)
                        );

                        int frameSize = canales * 2;
                        int tamanioBuffer = (int) Math.max(
                                TAMANO_MINIMO_BUFFER_AUDIO,
                                Math.min(TAMANO_MAXIMO_BUFFER_AUDIO,
                                        sampleRate * frameSize * DURACION_BUFFER_AUDIO_SEGUNDOS)
                        );
                        // Alinea el búfer a un número entero de frames.
                        tamanioBuffer = Math.max(frameSize,
                                tamanioBuffer - (tamanioBuffer % frameSize));

                        lineaAudio = AudioSystem.getSourceDataLine(formato);
                        lineaAudio.open(formato, tamanioBuffer);
                        lineaAudio.start();
                    }

                    // Solo conversión y escritura: el DSP ya terminó en el
                    // hilo de procesamiento.
                    byte[] bytesAudio = Util.convertirMuestrasABytesPCM(audioData.samples(), canales);
                    if (bytesAudio != null && bytesAudio.length > 0) {
                        lineaAudio.write(bytesAudio, 0, bytesAudio.length);
                    }
                }
                // La espera ya la realiza la cola bloqueante con timeout.
            }
        } catch (Exception ex) {
            System.err.println("[Controlador] Error en el ciclo de audio: " + ex.getMessage());
        } finally {
            if (lineaAudio != null) {
                lineaAudio.drain();
                lineaAudio.stop();
                lineaAudio.close();
            }
            // Cierra también el pipeline si la salida de audio falló o ya
            // terminó, para que ningún worker quede bloqueado publicando.
            servicio.detener();
        }
    }

    /**
     * Etapa DSP del pipeline. Extrae copias seguras producidas por el
     * decodificador, aplica el eco y publica el resultado para reproducción.
     */
    private void cicloProcesamientoAudio() {
        try {
            while (servicio.isEjecutando() || servicio.hayAudioPorProcesar()) {
                ServicioDecodificador.ContenedorAudio audioData = servicio.getSiguienteAudio();

                if (audioData == null) {
                    continue;
                }

                procesadorEco.procesar(
                        audioData.samples(),
                        audioData.numChannels(),
                        audioData.sampleRate()
                );

                if (!servicio.publicarAudioProcesado(audioData)) {
                    break;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            servicio.detener();
        } catch (Exception ex) {
            System.err.println("[Controlador] Error en el procesamiento DSP: " + ex.getMessage());
            servicio.detener();
        } finally {
            procesamientoAudioFinalizado = true;
        }
    }



}
