package cn.com.buildwin.omseefixed;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replaces the buggy native ffp_take_picture / ffp_record_video_start (which suffered from
 * YUVJ422P / YUV420P chroma height mismatch and DRI truncation leaving the bottom 50% gray #808080).
 *
 * Captures frames directly from the live OpenGL TextureView where RTP-JPEG FILL_DRI has already
 * reconstructed and rendered the full 640x480 frame cleanly.
 */
public class MediaCaptureEngine {
    private static final String TAG = "MediaCaptureEngine";
    private static final String FOLDER_NAME = "OMSeeFixed";
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int VIDEO_FPS = 25;
    private static final int VIDEO_BITRATE = 4_000_000;

    public interface CaptureCallback {
        void onPhotoSaved(File file, Bitmap preview);
        void onPhotoError(String message);
        void onVideoStarted();
        void onVideoTimerTick(long elapsedMillis);
        void onVideoSaved(File file);
        void onVideoError(String message);
    }

    private final Context mContext;
    private final CaptureCallback mCallback;

    private final AtomicBoolean mIsRecording = new AtomicBoolean(false);
    private HandlerThread mRecordThread;
    private Handler mRecordHandler;
    private MediaCodec mVideoEncoder;
    private MediaMuxer mMuxer;
    private Surface mEncoderInputSurface;
    private MediaCodec.BufferInfo mBufferInfo;
    private int mTrackIndex = -1;
    private boolean mMuxerStarted = false;
    private File mCurrentVideoFile;
    private long mRecordStartTimeMs = 0L;

    public MediaCaptureEngine(Context context, CaptureCallback callback) {
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    public static File getMediaDir(Context context) {
        File extPictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File dir = new File(extPictures, FOLDER_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            dir = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), FOLDER_NAME);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }
        return dir;
    }

    /**
     * Captures the exact full frame rendered on the TextureView, applying rotation, mirror, and zoom,
     * and saves a 100%-quality full-frame JPEG.
     */
    public void takePhoto(TextureView textureView, int videoWidth, int videoHeight,
                          int rotationDegrees, boolean mirrorHorizontal, float zoomFactor) {
        int srcW = videoWidth > 0 ? videoWidth : DEFAULT_WIDTH;
        int srcH = videoHeight > 0 ? videoHeight : DEFAULT_HEIGHT;

        // Grab raw un-transformed bitmap at stream resolution directly from the TextureView surface
        final Bitmap rawBitmap = textureView.getBitmap(srcW, srcH);
        if (rawBitmap == null) {
            if (mCallback != null) {
                mCallback.onPhotoError("El flujo de vídeo aún no está listo para capturar.");
            }
            return;
        }

        new Thread(() -> {
            try {
                Bitmap finalBitmap = transformBitmap(rawBitmap, rotationDegrees, mirrorHorizontal, zoomFactor);
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
                String fileName = "IMG_" + timestamp + ".jpg";

                File outDir = getMediaDir(mContext);
                File outFile = new File(outDir, fileName);

                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 98, fos);
                    fos.flush();
                }

                // Also index into MediaStore so it appears immediately in Google Photos / System Gallery
                indexFileToMediaStore(outFile, "image/jpeg", false);

                if (mCallback != null) {
                    mCallback.onPhotoSaved(outFile, finalBitmap);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving photo", e);
                if (mCallback != null) {
                    mCallback.onPhotoError("Error al guardar foto: " + e.getMessage());
                }
            }
        }, "PhotoSaveThread").start();
    }

    public boolean isRecording() {
        return mIsRecording.get();
    }

    public void startRecording(final TextureView textureView, final int videoWidth, final int videoHeight,
                               final int rotationDegrees, final boolean mirrorHorizontal, final float zoomFactor) {
        if (mIsRecording.get()) return;

        int baseW = videoWidth > 0 ? videoWidth : DEFAULT_WIDTH;
        int baseH = videoHeight > 0 ? videoHeight : DEFAULT_HEIGHT;
        final int outW = (rotationDegrees % 180 != 0) ? baseH : baseW;
        final int outH = (rotationDegrees % 180 != 0) ? baseW : baseH;
        // Ensure dimensions are multiples of 16 for hardware H.264 encoders
        final int encW = (outW / 16) * 16;
        final int encH = (outH / 16) * 16;

        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File outDir = getMediaDir(mContext);
            mCurrentVideoFile = new File(outDir, "VID_" + timestamp + ".mp4");

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            mVideoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoderInputSurface = mVideoEncoder.createInputSurface();
            mVideoEncoder.start();

            mMuxer = new MediaMuxer(mCurrentVideoFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mBufferInfo = new MediaCodec.BufferInfo();
            mTrackIndex = -1;
            mMuxerStarted = false;

            mIsRecording.set(true);
            mRecordStartTimeMs = System.currentTimeMillis();

            mRecordThread = new HandlerThread("VideoRecordThread");
            mRecordThread.start();
            mRecordHandler = new Handler(mRecordThread.getLooper());

            if (mCallback != null) {
                mCallback.onVideoStarted();
            }

            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            final Runnable frameTask = new Runnable() {
                @Override
                public void run() {
                    if (!mIsRecording.get()) return;
                    long loopStart = System.currentTimeMillis();
                    try {
                        Bitmap raw = textureView.getBitmap(baseW, baseH);
                        if (raw != null && mEncoderInputSurface != null) {
                            Bitmap transformed = transformBitmap(raw, rotationDegrees, mirrorHorizontal, zoomFactor);
                            Canvas canvas = mEncoderInputSurface.lockCanvas(null);
                            try {
                                canvas.drawColor(Color.BLACK);
                                Matrix scaleMatrix = new Matrix();
                                scaleMatrix.setScale(
                                        (float) encW / transformed.getWidth(),
                                        (float) encH / transformed.getHeight()
                                );
                                canvas.drawBitmap(transformed, scaleMatrix, paint);
                            } finally {
                                mEncoderInputSurface.unlockCanvasAndPost(canvas);
                            }
                            if (transformed != raw) {
                                transformed.recycle();
                            }
                            raw.recycle();
                        }
                        drainEncoder(false);
                        if (mCallback != null) {
                            mCallback.onVideoTimerTick(System.currentTimeMillis() - mRecordStartTimeMs);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Frame encode error", e);
                    }
                    if (mIsRecording.get() && mRecordHandler != null) {
                        long elapsed = System.currentTimeMillis() - loopStart;
                        long delay = Math.max(5L, (1000L / VIDEO_FPS) - elapsed);
                        mRecordHandler.postDelayed(this, delay);
                    }
                }
            };
            mRecordHandler.post(frameTask);

        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            mIsRecording.set(false);
            releaseEncoderResources();
            if (mCallback != null) {
                mCallback.onVideoError("Error al iniciar grabación: " + e.getMessage());
            }
        }
    }

    public void stopRecording() {
        if (!mIsRecording.getAndSet(false)) return;
        if (mRecordHandler != null) {
            mRecordHandler.post(() -> {
                try {
                    drainEncoder(true);
                } catch (Exception e) {
                    Log.e(TAG, "Error draining encoder on stop", e);
                } finally {
                    releaseEncoderResources();
                    if (mCurrentVideoFile != null && mCurrentVideoFile.exists()) {
                        indexFileToMediaStore(mCurrentVideoFile, "video/mp4", true);
                        if (mCallback != null) {
                            mCallback.onVideoSaved(mCurrentVideoFile);
                        }
                    }
                    if (mRecordThread != null) {
                        mRecordThread.quitSafely();
                        mRecordThread = null;
                    }
                }
            });
        }
    }

    private void drainEncoder(boolean endOfStream) {
        if (endOfStream && mVideoEncoder != null) {
            try {
                mVideoEncoder.signalEndOfInputStream();
            } catch (Exception ignored) {
            }
        }
        if (mVideoEncoder == null) return;

        while (true) {
            int encoderStatus = mVideoEncoder.dequeueOutputBuffer(mBufferInfo, endOfStream ? 10000 : 0);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!mMuxerStarted) {
                    MediaFormat newFormat = mVideoEncoder.getOutputFormat();
                    mTrackIndex = mMuxer.addTrack(newFormat);
                    mMuxer.start();
                    mMuxerStarted = true;
                }
            } else if (encoderStatus >= 0) {
                ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(encoderStatus);
                if (encodedData != null) {
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        mBufferInfo.size = 0;
                    }
                    if (mBufferInfo.size != 0 && mMuxerStarted) {
                        encodedData.position(mBufferInfo.offset);
                        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                        mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    }
                    mVideoEncoder.releaseOutputBuffer(encoderStatus, false);
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        }
    }

    private void releaseEncoderResources() {
        if (mVideoEncoder != null) {
            try {
                mVideoEncoder.stop();
            } catch (Exception ignored) {
            }
            try {
                mVideoEncoder.release();
            } catch (Exception ignored) {
            }
            mVideoEncoder = null;
        }
        if (mEncoderInputSurface != null) {
            try {
                mEncoderInputSurface.release();
            } catch (Exception ignored) {
            }
            mEncoderInputSurface = null;
        }
        if (mMuxer != null) {
            try {
                if (mMuxerStarted) {
                    mMuxer.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                mMuxer.release();
            } catch (Exception ignored) {
            }
            mMuxer = null;
            mMuxerStarted = false;
        }
    }

    public static Bitmap transformBitmap(Bitmap source, int rotationDegrees, boolean mirror, float zoom) {
        int w = source.getWidth();
        int h = source.getHeight();

        // Crop center according to digital zoom if zoom > 1.0f
        Bitmap cropped = source;
        if (zoom > 1.01f) {
            int cropW = Math.max(16, Math.round(w / zoom));
            int cropH = Math.max(16, Math.round(h / zoom));
            int startX = (w - cropW) / 2;
            int startY = (h - cropH) / 2;
            Bitmap zoomedCrop = Bitmap.createBitmap(source, startX, startY, cropW, cropH);
            cropped = Bitmap.createScaledBitmap(zoomedCrop, w, h, true);
            if (zoomedCrop != source && zoomedCrop != cropped) {
                zoomedCrop.recycle();
            }
        }

        if (rotationDegrees == 0 && !mirror) {
            return cropped;
        }

        Matrix m = new Matrix();
        if (mirror) {
            m.postScale(-1f, 1f, cropped.getWidth() / 2f, cropped.getHeight() / 2f);
        }
        if (rotationDegrees != 0) {
            m.postRotate(rotationDegrees);
        }
        Bitmap result = Bitmap.createBitmap(cropped, 0, 0, cropped.getWidth(), cropped.getHeight(), m, true);
        if (cropped != source && cropped != result) {
            cropped.recycle();
        }
        return result;
    }

    private void indexFileToMediaStore(File file, String mimeType, boolean isVideo) {
        try {
            // Scan the existing file in Pictures/OMSeeFixed so system gallery indexes it without creating a duplicate file
            MediaScannerConnection.scanFile(mContext, new String[]{file.getAbsolutePath()}, new String[]{mimeType}, null);
        } catch (Exception e) {
            Log.w(TAG, "MediaStore indexing warning: " + e.getMessage());
        }
    }
}
