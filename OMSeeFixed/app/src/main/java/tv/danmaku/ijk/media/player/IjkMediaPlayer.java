package tv.danmaku.ijk.media.player;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.lang.ref.WeakReference;

import tv.danmaku.ijk.media.player.misc.IAndroidIO;
import tv.danmaku.ijk.media.player.misc.IMediaDataSource;

public final class IjkMediaPlayer {
    private static final String TAG = "IjkMediaPlayer";

    public static final int OPT_CATEGORY_FORMAT = 1;
    public static final int OPT_CATEGORY_CODEC = 2;
    public static final int OPT_CATEGORY_SWS = 3;
    public static final int OPT_CATEGORY_PLAYER = 4;

    public static final int SDL_FCC_RV32 = 0x32335652;

    public static final int MEDIA_NOP = 0;
    public static final int MEDIA_PREPARED = 1;
    public static final int MEDIA_PLAYBACK_COMPLETE = 2;
    public static final int MEDIA_BUFFERING_UPDATE = 3;
    public static final int MEDIA_SEEK_COMPLETE = 4;
    public static final int MEDIA_SET_VIDEO_SIZE = 5;
    public static final int MEDIA_ERROR = 100;
    public static final int MEDIA_INFO = 200;
    public static final int RTCP_SR_DATA = 80000;
    public static final int DEVICE_CONNECTED = 90000;

    // Exact field names and types required by libijkplayer.so JNI_OnLoad
    private long mNativeMediaPlayer;
    private long mNativeMediaDataSource;
    private long mNativeAndroidIO;
    private int mNativeSurfaceTexture;
    private int mListenerContext;
    private int mVideoWidth;
    private int mVideoHeight;
    private int mVideoSarNum;
    private int mVideoSarDen;

    private final EventHandler mEventHandler;
    private Surface mSurface;

    public interface OnPreparedListener {
        void onPrepared(IjkMediaPlayer mp);
    }

    public interface OnVideoSizeChangedListener {
        void onVideoSizeChanged(IjkMediaPlayer mp, int width, int height, int sarNum, int sarDen);
    }

    public interface OnErrorListener {
        void onError(IjkMediaPlayer mp, int what, int extra);
    }

    public interface OnInfoListener {
        void onInfo(IjkMediaPlayer mp, int what, int extra);
    }

    public interface OnRtcpSrDataListener {
        void onRtcpSrData(IjkMediaPlayer mp, byte[] data);
    }

    public interface OnNativeEventLogListener {
        void onNativeEvent(String summary);
    }

    private OnPreparedListener mOnPreparedListener;
    private OnVideoSizeChangedListener mOnVideoSizeChangedListener;
    private OnErrorListener mOnErrorListener;
    private OnInfoListener mOnInfoListener;
    private OnRtcpSrDataListener mOnRtcpSrDataListener;
    private OnNativeEventLogListener mOnNativeEventLogListener;

    private static volatile boolean sIsLibLoaded = false;
    private static volatile boolean sIsNativeInitialized = false;

    public static void loadLibrariesOnce() {
        synchronized (IjkMediaPlayer.class) {
            if (!sIsLibLoaded) {
                System.loadLibrary("ijkffmpeg");
                System.loadLibrary("ijksdl");
                System.loadLibrary("ijkplayer");
                sIsLibLoaded = true;
            }
        }
    }

    private static void initNativeOnce() {
        synchronized (IjkMediaPlayer.class) {
            if (!sIsNativeInitialized) {
                native_init();
                sIsNativeInitialized = true;
            }
        }
    }

    public IjkMediaPlayer() {
        loadLibrariesOnce();
        initNativeOnce();
        setSpeed(1.0f);
        Looper looper = Looper.myLooper();
        if (looper != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }
        native_setup(new WeakReference<>(this));
    }

    public void setOnPreparedListener(OnPreparedListener listener) {
        mOnPreparedListener = listener;
    }

    public void setOnVideoSizeChangedListener(OnVideoSizeChangedListener listener) {
        mOnVideoSizeChangedListener = listener;
    }

    public void setOnErrorListener(OnErrorListener listener) {
        mOnErrorListener = listener;
    }

    public void setOnInfoListener(OnInfoListener listener) {
        mOnInfoListener = listener;
    }

    public void setOnRtcpSrDataListener(OnRtcpSrDataListener listener) {
        mOnRtcpSrDataListener = listener;
    }

    public void setOnNativeEventLogListener(OnNativeEventLogListener listener) {
        mOnNativeEventLogListener = listener;
    }

    public void setSpeed(float speed) {
        _setPropertyFloat(10003, speed);
    }

    public void setSurface(Surface surface) {
        mSurface = surface;
        _setVideoSurface(surface);
    }

    public void setDataSource(String path) {
        _setDataSource(path, null, null);
    }

    public void setOption(int category, String name, String value) {
        _setOption(category, name, value);
    }

    public void setOption(int category, String name, long value) {
        _setOption(category, name, value);
    }

    /**
     * Exact option set from cn.com.buildwin.flyScope.media.IjkVideoView.createPlayer() (0x36aacc)
     */
    public void configureForFlyScopeRtsp() {
        native_setLogLevel(3); // IJK_LOG_DEBUG
        setOption(OPT_CATEGORY_PLAYER, "mediacodec", 0L);
        setOption(OPT_CATEGORY_PLAYER, "opensles", 0L);
        setOption(OPT_CATEGORY_PLAYER, "overlay-format", (long) SDL_FCC_RV32);
        setOption(OPT_CATEGORY_PLAYER, "framedrop", 1L);
        setOption(OPT_CATEGORY_PLAYER, "start-on-prepared", 0L);

        setOption(OPT_CATEGORY_FORMAT, "initial_timeout", 500000L);
        setOption(OPT_CATEGORY_FORMAT, "stimeout", 500000L);
        setOption(OPT_CATEGORY_FORMAT, "http-detect-range-support", 0L);

        setOption(OPT_CATEGORY_CODEC, "skip_loop_filter", 48L);

        // Exact value from IjkVideoView.<init>: mRtpJpegParsePacketMethod = 1
        setOption(OPT_CATEGORY_PLAYER, "rtp-jpeg-parse-packet-method", 1L);
        setOption(OPT_CATEGORY_PLAYER, "readtimeout", 75000000L);

        setOption(OPT_CATEGORY_PLAYER, "preferred-image-type", 0L);
        setOption(OPT_CATEGORY_PLAYER, "image-quality-min", 2L);
        setOption(OPT_CATEGORY_PLAYER, "image-quality-max", 20L);
        setOption(OPT_CATEGORY_PLAYER, "preferred-video-type", 2L);
        setOption(OPT_CATEGORY_PLAYER, "video-need-transcoding", 1L);
        setOption(OPT_CATEGORY_PLAYER, "mjpeg-pix-fmt", 1L);
        setOption(OPT_CATEGORY_PLAYER, "video-quality-min", 2L);
        setOption(OPT_CATEGORY_PLAYER, "video-quality-max", 20L);
        setOption(OPT_CATEGORY_PLAYER, "x264-option-preset", 0L);
        setOption(OPT_CATEGORY_PLAYER, "x264-option-tune", 5L);
        setOption(OPT_CATEGORY_PLAYER, "x264-option-profile", 1L);
        setOption(OPT_CATEGORY_PLAYER, "x264-params", "crf=20");
    }

    public void prepareAsync() {
        _prepareAsync();
    }

    public void start() {
        _start();
    }

    public void stop() {
        _stop();
    }

    public void pause() {
        _pause();
    }

    public void reset() {
        _reset();
        if (mEventHandler != null) {
            mEventHandler.removeCallbacksAndMessages(null);
        }
        mVideoWidth = 0;
        mVideoHeight = 0;
    }

    public void release() {
        _release();
        if (mEventHandler != null) {
            mEventHandler.removeCallbacksAndMessages(null);
        }
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    public boolean setRotation180(boolean rotate) {
        return _setRotation180(rotate);
    }

    public boolean setVrMode(boolean vrMode) {
        return _setVrMode(vrMode);
    }

    // Native methods registered by libijkplayer.so
    private native void _setDataSource(String path, String[] keys, String[] values);
    private native void _setDataSourceFd(int fd);
    private native void _setDataSource(IMediaDataSource mediaDataSource);
    private native void _setAndroidIOCallback(IAndroidIO androidIO);
    private native void _setVideoSurface(Surface surface);
    public native void _prepareAsync();
    private native void _start();
    private native void _stop();
    public native void seekTo(long msec);
    private native void _pause();
    public native boolean isPlaying();
    public native long getCurrentPosition();
    public native long getDuration();
    private native void _release();
    private native void _reset();
    public native void setVolume(float leftVolume, float rightVolume);
    public native int getAudioSessionId();
    private static native void native_init();
    private native void native_setup(Object IjkMediaPlayer_this);
    private native void native_finalize();
    private native void _setOption(int category, String name, String value);
    private native void _setOption(int category, String name, long value);
    private native String _getColorFormatName(int mediaCodecColorFormat);
    private native String _getVideoCodecInfo();
    private native String _getAudioCodecInfo();
    private native Bundle _getMediaMeta();
    private native void _setLoopCount(int loopCount);
    private native int _getLoopCount();
    private native float _getPropertyFloat(int property, float defaultValue);
    private native void _setPropertyFloat(int property, float value);
    private native long _getPropertyLong(int property, long defaultValue);
    private native void _setPropertyLong(int property, long value);
    private native void _setStreamSelected(int stream, boolean select);
    public static native void native_profileBegin(String libName);
    public static native void native_profileEnd();
    public static native void native_setLogLevel(int level);
    private native void _injectCacheNode(int index, long fpos, long lpos, long dpos, long size);
    private native void _setFrameAtTime(String imgCachePath, long startTime, long endTime, int num, int imgDefinition);
    private native void _sendRtcpRrData(byte[] data, int size);
    private native void _takePicture(String filePath, String fileName, int width, int height, int number);
    private native void _startRecordVideo(String filePath, String fileName, int width, int height);
    private native void _stopRecordVideo();
    private native boolean _setRotation180(boolean rotation180);
    private native boolean _setVrMode(boolean vrMode);
    private native void _setTexcoordRect(float x0, float x1, float y0, float y1);

    @Override
    protected void finalize() throws Throwable {
        try {
            native_finalize();
        } finally {
            super.finalize();
        }
    }

    // Callbacks invoked directly from native C code
    @SuppressWarnings("unused")
    private static void postEventFromNative(Object weakThiz, int what, int arg1, int arg2, Object obj) {
        if (weakThiz == null) return;
        @SuppressWarnings("unchecked")
        IjkMediaPlayer mp = ((WeakReference<IjkMediaPlayer>) weakThiz).get();
        if (mp == null) return;
        if (what == MEDIA_INFO && arg1 == 2) {
            // Exact behavior from original IjkMediaPlayer.postEventFromNative (0x164060):
            // when what == 200 (MEDIA_INFO) and arg1 == 2 (MEDIA_INFO_STARTED_AS_NEXT), calls start()
            mp.start();
        }
        if (mp.mEventHandler != null) {
            Message m = mp.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            mp.mEventHandler.sendMessage(m);
        }
    }

    @SuppressWarnings("unused")
    private static String onSelectCodec(Object weakThiz, String mimeType, int profile, int level) {
        return null;
    }

    /**
     * CRITICAL: Must return false (0) when no OnNativeInvokeListener is set, exactly like
     * classes2.dex IjkMediaPlayer.onNativeInvoke (0x163558).
     */
    @SuppressWarnings("unused")
    private static boolean onNativeInvoke(Object weakThiz, int what, Bundle args) {
        if (weakThiz instanceof WeakReference) {
            Object obj = ((WeakReference<?>) weakThiz).get();
            if (obj instanceof IjkMediaPlayer) {
                IjkMediaPlayer mp = (IjkMediaPlayer) obj;
                if (mp.mEventHandler != null && mp.mOnNativeEventLogListener != null) {
                    String ip = args != null ? args.getString("ip", "") : "";
                    int port = args != null ? args.getInt("port", 0) : 0;
                    int err = args != null ? args.getInt("error", 0) : 0;
                    String summary = String.format("onNativeInvoke(0x%x) ip=%s:%d err=%d", what, ip, port, err);
                    mp.mEventHandler.post(() -> {
                        if (mp.mOnNativeEventLogListener != null) {
                            mp.mOnNativeEventLogListener.onNativeEvent(summary);
                        }
                    });
                }
            }
        }
        return false;
    }

    private static class EventHandler extends Handler {
        private final WeakReference<IjkMediaPlayer> mWeakPlayer;

        public EventHandler(IjkMediaPlayer mp, Looper looper) {
            super(looper);
            mWeakPlayer = new WeakReference<>(mp);
        }

        @Override
        public void handleMessage(Message msg) {
            IjkMediaPlayer player = mWeakPlayer.get();
            if (player == null || player.mNativeMediaPlayer == 0) {
                return;
            }
            if (player.mOnNativeEventLogListener != null && msg.what != RTCP_SR_DATA) {
                player.mOnNativeEventLogListener.onNativeEvent(
                        "NativeEvent what=" + msg.what + " arg1=" + msg.arg1 + " arg2=" + msg.arg2);
            }
            switch (msg.what) {
                case MEDIA_PREPARED:
                    if (player.mOnPreparedListener != null) {
                        player.mOnPreparedListener.onPrepared(player);
                    }
                    break;
                case MEDIA_SET_VIDEO_SIZE:
                    player.mVideoWidth = msg.arg1;
                    player.mVideoHeight = msg.arg2;
                    if (player.mOnVideoSizeChangedListener != null) {
                        player.mOnVideoSizeChangedListener.onVideoSizeChanged(
                                player, player.mVideoWidth, player.mVideoHeight,
                                player.mVideoSarNum, player.mVideoSarDen);
                    }
                    break;
                case MEDIA_INFO:
                    if (player.mOnInfoListener != null) {
                        player.mOnInfoListener.onInfo(player, msg.arg1, msg.arg2);
                    }
                    break;
                case MEDIA_ERROR:
                case MEDIA_PLAYBACK_COMPLETE:
                    Log.e(TAG, "Player event what=" + msg.what + " arg1=" + msg.arg1 + " arg2=" + msg.arg2);
                    if (player.mOnErrorListener != null) {
                        player.mOnErrorListener.onError(player, msg.what, msg.arg1);
                    }
                    break;
                case RTCP_SR_DATA:
                    if (msg.obj instanceof byte[] && player.mOnRtcpSrDataListener != null) {
                        player.mOnRtcpSrDataListener.onRtcpSrData(player, (byte[]) msg.obj);
                    }
                    break;
                case DEVICE_CONNECTED:
                    if (player.mOnInfoListener != null) {
                        player.mOnInfoListener.onInfo(player, DEVICE_CONNECTED, msg.arg1);
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
