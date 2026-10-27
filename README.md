# OMSee Fixed (Android Digital Microscope Client)

Clon limpio y reparado de la aplicación Android **OMSee / FlyScope** (`cn.com.buildwin.FlyScope` vB3000.01.10) para microscopios digitales Wi-Fi basados en chips Buildwin (`rtsp://192.168.1.1:7070/webcam`).

## Problema en la aplicación original (`OMSee_B3000.01.10`)

En la aplicación oficial, aunque la vista previa en vivo se muestra completa, **todas las fotos (`.jpg`) y vídeos (`.mp4`) guardados salen cortados por la mitad** (con el 50 % inferior en gris sólido `#808080`, es decir `Y=128, U=128, V=128` en YUV).

### Causa raíz descubierta mediante ingeniería inversa (REA)
1. **Incompatibilidad de submuestreo cromático YUV422P → YUV420P en `ffp_take_picture` y `ffp_record_video_start` (`libijkplayer.so`)**:
   - El flujo MJPEG del microscopio codifica los fotogramas en `AV_PIX_FMT_YUVJ422P` (640×480), pero el codificador nativo de captura de `libijkplayer.so` reserva y procesa planos asumiendo `AV_PIX_FMT_YUV420P` sin convertir la altura de croma (`height >> 1`) ni rellenar los intervalos DRI (`0xFFDD`) perdidos por UDP antes de re-codificar.
2. **Enrutamiento de red en Android 10+**:
   - Como el punto de acceso Wi-Fi del microscopio (`FlyScope_xxx`) no tiene salida a Internet, Android desvía por defecto los sockets TCP/UDP hacia los datos móviles a menos que el proceso se vincule explícitamente a la red `TRANSPORT_WIFI` mediante `ConnectivityManager.bindProcessToNetwork(...)`.

## Solución implementada en `OMSeeFixed`

- **Motor de reproducción RTSP de baja latencia (`IjkMediaPlayer` + `libijkplayer.so`)**:
  - Configura `rtp-jpeg-parse-packet-method = 1` y decodificación de vídeo hacia `TextureView` acelerado por hardware (`SDL_FCC_RV32`).
  - Vinculación automática a la red Wi-Fi del microscopio y detección automática de puerta de enlace (`192.168.1.1:7070`).
- **Motor de captura 100 % completo (`MediaCaptureEngine`)**:
  - **Fotos JPEG**: Captura el fotograma completo directamente desde el buffer OpenGL ya reconstruido del `TextureView`, respetando rotación (`0°/90°/180°/270°`), espejo y zoom digital (`1.0x` – `4.0x`).
  - **Vídeos MP4 (H.264)**: Codificación por hardware mediante `MediaCodec` (` video/avc`) + `MediaMuxer` a 25 fps, generando archivos `.mp4` completos y compatibles con cualquier reproductor.
- **Soporte para botones físicos del microscopio**:
  - Decodifica los paquetes RTCP SR (`0x0F 0x5A 0x1E 0x69`) enviados al pulsar los botones físicos de foto, vídeo, zoom `+`/`-` y máscara ocular en el cuerpo del microscopio.
- **Galería integrada (`GalleryActivity`)**:
  - Permite revisar, reproducir, compartir y borrar las fotos y vídeos capturados sin salir de la aplicación.

## Estructura del repositorio

- `OMSeeFixed/`: Proyecto Android completo (Gradle).
- `OMSee_Fixed.apk`: APK compilado y listo para instalar en Android (`arm64-v8a`).
- `OMSee_B3000.01.10_APKPure.xapk`: Paquete original de referencia analizado.
