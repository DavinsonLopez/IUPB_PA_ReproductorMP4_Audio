package controladores;

import org.bytedeco.javacv.Java2DFrameConverter;

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

    private final VistaReproductor vista;
    private final ServicioDecodificador servicio;
    private long ultimoTiempo;

    public ControladorReproductor(VistaReproductor vista,
                                  ServicioDecodificador servicio) {
        this.vista = vista;
        this.servicio = servicio;

        this.vista.setSelecionarClick(evento -> iniciarProceso());
    }

    private void cicloRenderizado() {
        try (var conversor = new Java2DFrameConverter()) {
            while (servicio.isEjecutando()) {
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

        // 1. Hilo PRODUCTOR (decodifica video y audio concurrentemente y los encola)
        new Thread(() -> servicio.iniciarDecodificacion(archivo.getAbsolutePath()), "Hilo Decodificacion").start();

        // 2. Hilo CONSUMIDOR DE VIDEO (muestra las imágenes)
        new Thread(this::cicloRenderizado, "Hilo Renderizado").start();

        // 3. NUEVO: Hilo CONSUMIDOR DE AUDIO (reproduce el sonido en paralelo)
        new Thread(this::cicloAudio, "Hilo Audio").start();

        // 4. Hilo TELEMETRIA
        new Thread(this::cicloTelemetria, "Hilo Telemetria").start();
    }

    private void cicloAudio() {
        SourceDataLine lineaAudio = null;
        try {
            while (servicio.isEjecutando()) {
                ServicioDecodificador.ContenedorAudio audioData = servicio.getSiguienteAudio();

                if (audioData != null && audioData.samples() != null && audioData.samples().length > 0) {
                    int canales = audioData.numChannels();
                    float sampleRate = audioData.sampleRate();

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

                        lineaAudio = AudioSystem.getSourceDataLine(formato);
                        lineaAudio.open(formato);
                        lineaAudio.start();
                    }

                    Util.aplicarEfectoEcoSincrono(audioData.samples(), audioData.numChannels());

                    // Convertir los buffers de JavaCV a un arreglo de bytes PCM estándar de 16 bits
                    byte[] bytesAudio = Util.convertirMuestrasABytesPCM(audioData.samples(), canales);
                    if (bytesAudio != null && bytesAudio.length > 0) {
                        lineaAudio.write(bytesAudio, 0, bytesAudio.length);
                    }
                } else {
                    Thread.sleep(2); // Pequeña pausa si la cola está momentáneamente vacía
                }
            }
        } catch (Exception ex) {
            System.err.println("[Controlador] Error en el ciclo de audio: " + ex.getMessage());
        } finally {
            if (lineaAudio != null) {
                lineaAudio.drain();
                lineaAudio.stop();
                lineaAudio.close();
            }
        }
    }



}