package cn.com.buildwin.omseefixed;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaActionSound;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener, MediaCaptureEngine.CaptureCallback {
    private static final String DEFAULT_RTSP_HOST = "192.168.1.1";
    private static final int RTSP_PORT = 7070;

    private String mActiveRtspHost = DEFAULT_RTSP_HOST;
    private String mActiveRtspUrl = "rtsp://192.168.1.1:7070/webcam";

    private FrameLayout mVideoContainer;
    private TextureView mTextureView;
    private ScopeOverlayView mScopeOverlayView;
    private View mFlashOverlay;

    private TextView mStatusDot;
    private TextView mStatusLabel;
    private TextView mTimerBadge;
    private LinearLayout mDiagPanel;
    private TextView mDiagText;

    private Button mPhotoBtn;
    private Button mVideoBtn;
    private Button mZoomBtn;
    private Button mRotateBtn;
    private Button mMirrorBtn;
    private Button mMaskBtn;
    private Button mGalleryBtn;
    private ImageView mLastThumbView;

    private IjkMediaPlayer mMediaPlayer;
    private Surface mSurface;
    private MediaCaptureEngine mCaptureEngine;
    private MediaActionSound mShutterSound;
    private ScaleGestureDetector mScaleDetector;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<String> mDiagLines = new ArrayList<>();
    private boolean mIsConnected = false;
    private boolean mIsConnecting = false;
    private boolean mWifiBound = false;
    private int mVideoWidth = 640;
    private int mVideoHeight = 480;
    private long mLastPhotoCaptureTimeMs = 0L;
    private long mLastHwCmdTimeMs = 0L;

    // Default to 90 degrees like the original OMSee app, user-cyclable (0, 90, 180, 270)
    private int mRotationDegrees = 90;
    private boolean mMirrorHorizontal = false;
    private float mZoomFactor = 1.0f;
    private boolean mShowCircleMask = false;

    private ConnectivityManager mConnectivityManager;
    private ConnectivityManager.NetworkCallback mWifiNetworkCallback;

    private final Runnable mReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsConnected && !mIsConnecting && mSurface != null) {
                startRtspStream();
            }
        }
    };

    private final Runnable mConnectionWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (mIsConnecting && !mIsConnected) {
                appendDiag("Watchdog 6s: prepareAsync no completó. Volcando log nativo...");
                dumpRecentNativeLogs();
                mIsConnecting = false;
                startRtspStream();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // Pre-load native libraries and start profile exactly like RtspPreviewActivity.initData()
        IjkMediaPlayer.loadLibrariesOnce();
        IjkMediaPlayer.native_profileBegin("libijkplayer.so");

        mCaptureEngine = new MediaCaptureEngine(this, this);
        mShutterSound = new MediaActionSound();
        mShutterSound.load(MediaActionSound.SHUTTER_CLICK);

        buildUserInterface();
        appendDiag("App iniciada. Librerías nativas ijkffmpeg/ijksdl/ijkplayer cargadas.");
        requestPermissionsIfNeeded();
        bindToMicroscopeWifiNetwork();
    }

    private void buildUserInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // Video container
        mVideoContainer = new FrameLayout(this);
        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);

        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                mZoomFactor = Math.max(1.0f, Math.min(4.0f, mZoomFactor * detector.getScaleFactor()));
                updateZoomLabel();
                applyTextureViewTransform();
                return true;
            }
        });
        mTextureView.setOnTouchListener((v, event) -> {
            mScaleDetector.onTouchEvent(event);
            return true;
        });

        mVideoContainer.addView(mTextureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Optional circular ocular mask (AI mode on hardware button 10)
        mScopeOverlayView = new ScopeOverlayView(this);
        mScopeOverlayView.setVisibility(View.GONE);
        mVideoContainer.addView(mScopeOverlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // White flash overlay for photo feedback
        mFlashOverlay = new View(this);
        mFlashOverlay.setBackgroundColor(Color.WHITE);
        mFlashOverlay.setAlpha(0f);
        mVideoContainer.addView(mFlashOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(mVideoContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top HUD container (Bar + Collapsible Diagnostic Log)
        LinearLayout topContainer = new LinearLayout(this);
        topContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topHud = new LinearLayout(this);
        topHud.setOrientation(LinearLayout.HORIZONTAL);
        topHud.setGravity(Gravity.CENTER_VERTICAL);
        topHud.setPadding(dp(12), dp(12), dp(12), dp(12));
        topHud.setBackgroundColor(Color.parseColor("#B30B1017"));

        mStatusDot = new TextView(this);
        mStatusDot.setText("●");
        mStatusDot.setTextColor(Color.parseColor("#FFB300"));
        mStatusDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        topHud.addView(mStatusDot);

        mStatusLabel = new TextView(this);
        mStatusLabel.setText(" Buscando Wi-Fi del microscopio...");
        mStatusLabel.setTextColor(Color.WHITE);
        mStatusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topHud.addView(mStatusLabel, statusLp);

        mTimerBadge = new TextView(this);
        mTimerBadge.setText("● REC 00:00");
        mTimerBadge.setTextColor(Color.WHITE);
        mTimerBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mTimerBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable recBg = new GradientDrawable();
        recBg.setColor(Color.parseColor("#E53935"));
        recBg.setCornerRadius(dp(12));
        mTimerBadge.setBackground(recBg);
        mTimerBadge.setVisibility(View.GONE);
        topHud.addView(mTimerBadge);

        Button diagBtn = createHudButton("📋 Log", "#263245");
        diagBtn.setOnClickListener(v -> {
            if (mDiagPanel.getVisibility() == View.VISIBLE) {
                mDiagPanel.setVisibility(View.GONE);
            } else {
                dumpRecentNativeLogs();
                mDiagPanel.setVisibility(View.VISIBLE);
            }
        });
        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        diagLp.leftMargin = dp(6);
        topHud.addView(diagBtn, diagLp);

        Button reconnectBtn = createHudButton("↻ Reconectar", "#1565C0");
        reconnectBtn.setOnClickListener(v -> {
            appendDiag("Usuario pulsó Reconectar.");
            mIsConnecting = false;
            mIsConnected = false;
            bindToMicroscopeWifiNetwork();
            startRtspStream();
        });
        LinearLayout.LayoutParams reconLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reconLp.leftMargin = dp(6);
        topHud.addView(reconnectBtn, reconLp);

        topContainer.addView(topHud, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Diagnostic overlay panel (visible automatically while connecting, hides when connected)
        mDiagPanel = new LinearLayout(this);
        mDiagPanel.setOrientation(LinearLayout.VERTICAL);
        mDiagPanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        mDiagPanel.setBackgroundColor(Color.parseColor("#CC0A1118"));

        LinearLayout diagHeader = new LinearLayout(this);
        diagHeader.setOrientation(LinearLayout.HORIZONTAL);
        diagHeader.setGravity(Gravity.CENTER_VERTICAL);

        TextView diagTitle = new TextView(this);
        diagTitle.setText("Diagnóstico en vivo RTSP / Red:");
        diagTitle.setTextColor(Color.parseColor("#64B5F6"));
        diagTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        diagTitle.setTypeface(Typeface.DEFAULT_BOLD);
        diagHeader.addView(diagTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copyDiagBtn = createHudButton("Copiar Log", "#37474F");
        copyDiagBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("OMSeeLog", mDiagText.getText()));
                Toast.makeText(this, "Diagnóstico copiado al portapapeles", Toast.LENGTH_SHORT).show();
            }
        });
        diagHeader.addView(copyDiagBtn);
        mDiagPanel.addView(diagHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView diagScroll = new ScrollView(this);
        mDiagText = new TextView(this);
        mDiagText.setTextColor(Color.parseColor("#E0E0E0"));
        mDiagText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mDiagText.setTypeface(Typeface.MONOSPACE);
        diagScroll.addView(mDiagText);
        mDiagPanel.addView(diagScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));

        topContainer.addView(mDiagPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(topContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Bottom control dock
        LinearLayout bottomDock = new LinearLayout(this);
        bottomDock.setOrientation(LinearLayout.VERTICAL);
        bottomDock.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomDock.setPadding(dp(10), dp(10), dp(10), dp(18));
        bottomDock.setBackgroundColor(Color.parseColor("#B30B1017"));

        // Secondary adjustment row (Zoom, Rotation, Mirror, Ocular Mask)
        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        toolsRow.setGravity(Gravity.CENTER);
        toolsRow.setPadding(0, 0, 0, dp(12));

        mZoomBtn = createHudButton("🔍 1.0x", "#222E40");
        mZoomBtn.setOnClickListener(v -> cycleZoom());
        toolsRow.addView(mZoomBtn, createToolWeightParams());

        mRotateBtn = createHudButton("⟳ 90°", "#222E40");
        mRotateBtn.setOnClickListener(v -> {
            mRotationDegrees = (mRotationDegrees + 90) % 360;
            mRotateBtn.setText("⟳ " + mRotationDegrees + "°");
            applyTextureViewTransform();
        });
        toolsRow.addView(mRotateBtn, createToolWeightParams());

        mMirrorBtn = createHudButton("⇄ Espejo", "#222E40");
        mMirrorBtn.setOnClickListener(v -> {
            mMirrorHorizontal = !mMirrorHorizontal;
            mMirrorBtn.setText(mMirrorHorizontal ? "⇄ Espejo: Sí" : "⇄ Espejo");
            applyTextureViewTransform();
        });
        toolsRow.addView(mMirrorBtn, createToolWeightParams());

        mMaskBtn = createHudButton("◯ Ocular", "#222E40");
        mMaskBtn.setOnClickListener(v -> {
            mShowCircleMask = !mShowCircleMask;
            mScopeOverlayView.setVisibility(mShowCircleMask ? View.VISIBLE : View.GONE);
            mMaskBtn.setText(mShowCircleMask ? "◉ Ocular" : "◯ Ocular");
        });
        toolsRow.addView(mMaskBtn, createToolWeightParams());

        bottomDock.addView(toolsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Primary capture row (Gallery preview, Take Photo, Record Video)
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        // Last capture thumbnail + Gallery button
        LinearLayout galleryBox = new LinearLayout(this);
        galleryBox.setOrientation(LinearLayout.HORIZONTAL);
        galleryBox.setGravity(Gravity.CENTER_VERTICAL);

        mLastThumbView = new ImageView(this);
        mLastThumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mLastThumbView.setBackgroundColor(Color.parseColor("#2A364B"));
        mLastThumbView.setOnClickListener(v -> openGallery());
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        thumbLp.rightMargin = dp(6);
        galleryBox.addView(mLastThumbView, thumbLp);

        mGalleryBtn = createHudButton("🖼 Galería", "#2B3950");
        mGalleryBtn.setOnClickListener(v -> openGallery());
        galleryBox.addView(mGalleryBtn);

        actionRow.addView(galleryBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Big Photo Button
        mPhotoBtn = createPrimaryButton("📸 FOTO", "#1E88E5");
        mPhotoBtn.setOnClickListener(v -> triggerPhotoCapture());
        LinearLayout.LayoutParams photoLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        photoLp.leftMargin = dp(8);
        photoLp.rightMargin = dp(8);
        actionRow.addView(mPhotoBtn, photoLp);

        // Big Video Button
        mVideoBtn = createPrimaryButton("⏺ VÍDEO", "#D81B60");
        mVideoBtn.setOnClickListener(v -> toggleVideoRecording());
        LinearLayout.LayoutParams videoLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        actionRow.addView(mVideoBtn, videoLp);

        bottomDock.addView(actionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bottomDock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
    }

    private void appendDiag(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = "[" + ts + "] " + msg;
        mMainHandler.post(() -> {
            mDiagLines.add(line);
            while (mDiagLines.size() > 35) {
                mDiagLines.remove(0);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mDiagLines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(mDiagLines.get(i));
            }
            if (mDiagText != null) {
                mDiagText.setText(sb.toString());
            }
        });
    }

    private void dumpRecentNativeLogs() {
        new Thread(() -> {
            try {
                int myPid = android.os.Process.myPid();
                Process proc = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "25", "--pid=" + myPid});
                BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("IJKMEDIA") || line.contains("IjkMediaPlayer") ||
                        line.contains("ffp_") || line.contains("rtsp") || line.contains("tcp")) {
                        int idx = line.indexOf(": ");
                        String clean = (idx > 0 && idx + 2 < line.length()) ? line.substring(idx + 2) : line;
                        appendDiag("LOG: " + clean);
                    }
                }
                br.close();
            } catch (Exception ignored) {
            }
        }, "LogcatReader").start();
    }

    /**
     * Exact replication of HomeActivity.setWifi() + gateway auto-detection so that
     * native FFmpeg sockets route through the microscope Wi-Fi immediately.
     */
    private void bindToMicroscopeWifiNetwork() {
        try {
            mConnectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (mConnectivityManager == null) return;

            Network[] networks = mConnectivityManager.getAllNetworks();
            if (networks != null) {
                for (Network net : networks) {
                    NetworkCapabilities caps = mConnectivityManager.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        bindAndInspectWifiNetwork(net);
                        break;
                    }
                }
            }

            if (mWifiNetworkCallback != null) {
                try {
                    mConnectivityManager.unregisterNetworkCallback(mWifiNetworkCallback);
                } catch (Exception ignored) {
                }
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();

            mWifiNetworkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    boolean wasBound = mWifiBound;
                    bindAndInspectWifiNetwork(network);
                    mMainHandler.post(() -> {
                        if (!wasBound || (!mIsConnected && !mIsConnecting)) {
                            startRtspStream();
                        }
                    });
                }

                @Override
                public void onLost(Network network) {
                    mWifiBound = false;
                    appendDiag("Wi-Fi desconectado (onLost).");
                    mConnectivityManager.bindProcessToNetwork(null);
                }
            };
            mConnectivityManager.registerNetworkCallback(request, mWifiNetworkCallback);
        } catch (Exception e) {
            appendDiag("Error bindToMicroscopeWifiNetwork: " + e.getMessage());
        }
    }

    private void bindAndInspectWifiNetwork(Network net) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean ok = mConnectivityManager.bindProcessToNetwork(net);
                mWifiBound = ok;
            } else {
                ConnectivityManager.setProcessDefaultNetwork(net);
                mWifiBound = true;
            }
            LinkProperties lp = mConnectivityManager.getLinkProperties(net);
            String localIp = "";
            String gatewayIp = "";
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress addr = la.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        localIp = addr.getHostAddress();
                    }
                }
                for (RouteInfo route : lp.getRoutes()) {
                    InetAddress gw = route.getGateway();
                    if (gw instanceof Inet4Address && !gw.isAnyLocalAddress() && !gw.isLoopbackAddress()) {
                        gatewayIp = gw.getHostAddress();
                        break;
                    }
                }
            }
            if (gatewayIp == null || gatewayIp.isEmpty() || "0.0.0.0".equals(gatewayIp)) {
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null && wm.getDhcpInfo() != null) {
                        DhcpInfo dhcp = wm.getDhcpInfo();
                        if (dhcp.gateway != 0) {
                            gatewayIp = intToIp(dhcp.gateway);
                        }
                        if ((localIp == null || localIp.isEmpty()) && dhcp.ipAddress != 0) {
                            localIp = intToIp(dhcp.ipAddress);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (gatewayIp != null && !gatewayIp.isEmpty() && !"0.0.0.0".equals(gatewayIp)) {
                mActiveRtspHost = gatewayIp;
            } else if (localIp != null && localIp.startsWith("192.168.")) {
                int lastDot = localIp.lastIndexOf('.');
                if (lastDot > 0) {
                    mActiveRtspHost = localIp.substring(0, lastDot + 1) + "1";
                }
            } else {
                mActiveRtspHost = DEFAULT_RTSP_HOST;
            }
            mActiveRtspUrl = "rtsp://" + mActiveRtspHost + ":" + RTSP_PORT + "/webcam";
            appendDiag("Wi-Fi vinculado=" + mWifiBound + " IP=" + localIp + " GW=" + mActiveRtspHost);
        } catch (Exception e) {
            appendDiag("Error inspeccionando Wi-Fi: " + e.getMessage());
        }
    }

    private static String intToIp(int addr) {
        return (addr & 0xFF) + "." +
               ((addr >> 8) & 0xFF) + "." +
               ((addr >> 16) & 0xFF) + "." +
               ((addr >> 24) & 0xFF);
    }

    private void startRtspStream() {
        mMainHandler.removeCallbacks(mReconnectRunnable);
        mMainHandler.removeCallbacks(mConnectionWatchdogRunnable);
        releasePlayer();
        if (mSurface == null) return;

        mIsConnecting = true;
        mIsConnected = false;
        updateConnectionStatus("Conectando a " + mActiveRtspUrl + "...", "#FFB300");
        appendDiag("Iniciando stream: " + mActiveRtspUrl);

        // Quick TCP probe to active gateway:7070 on background thread for clear diagnostics
        final String probeHost = mActiveRtspHost;
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress(probeHost, RTSP_PORT), 1500);
                long dt = System.currentTimeMillis() - t0;
                appendDiag("TCP " + probeHost + ":" + RTSP_PORT + " ABIERTO (" + dt + " ms)");
            } catch (Exception e) {
                appendDiag("TCP " + probeHost + ":" + RTSP_PORT + " FALLÓ: " + e.getMessage());
                String wifiHint = mWifiBound ? "Wi-Fi vinculado" : "Sin Wi-Fi vinculado";
                updateConnectionStatus("Sin respuesta en " + probeHost + ":" + RTSP_PORT + " (" + wifiHint + ")...", "#FF9100");
            }
        }, "RtspProbeThread").start();

        try {
            mMediaPlayer = new IjkMediaPlayer();
            mMediaPlayer.setOnNativeEventLogListener(summary -> appendDiag(summary));
            mMediaPlayer.configureForFlyScopeRtsp();

            mMediaPlayer.setOnPreparedListener(mp -> {
                mMainHandler.removeCallbacks(mConnectionWatchdogRunnable);
                mIsConnecting = false;
                mIsConnected = true;
                appendDiag("MEDIA_PREPARED recibido -> iniciando reproducción.");
                mp.start();
                mDiagPanel.setVisibility(View.GONE);
                updateConnectionStatus(
                        String.format(Locale.US, "En vivo (%dx%d) • Captura 100%% reparada", mVideoWidth, mVideoHeight),
                        "#00E676"
                );
                applyTextureViewTransform();
            });

            mMediaPlayer.setOnVideoSizeChangedListener((mp, width, height, sarNum, sarDen) -> {
                if (width > 0 && height > 0) {
                    mVideoWidth = width;
                    mVideoHeight = height;
                    mIsConnecting = false;
                    mIsConnected = true;
                    appendDiag("Tamaño de vídeo detectado: " + width + "x" + height);
                    mDiagPanel.setVisibility(View.GONE);
                    updateConnectionStatus(
                            String.format(Locale.US, "En vivo (%dx%d) • Captura 100%% reparada", mVideoWidth, mVideoHeight),
                            "#00E676"
                    );
                    applyTextureViewTransform();
                }
            });

            mMediaPlayer.setOnInfoListener((mp, what, extra) -> {
                if (what == 3) { // MEDIA_INFO_VIDEO_RENDERING_START
                    mMainHandler.removeCallbacks(mConnectionWatchdogRunnable);
                    mIsConnecting = false;
                    mIsConnected = true;
                    appendDiag("Primer fotograma renderizado (MEDIA_INFO_VIDEO_RENDERING_START).");
                    mDiagPanel.setVisibility(View.GONE);
                    updateConnectionStatus(
                            String.format(Locale.US, "En vivo (%dx%d) • Captura 100%% reparada", mVideoWidth, mVideoHeight),
                            "#00E676"
                    );
                }
            });

            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                mMainHandler.removeCallbacks(mConnectionWatchdogRunnable);
                mIsConnecting = false;
                mIsConnected = false;
                appendDiag("MEDIA_ERROR what=" + what + " extra=" + extra);
                dumpRecentNativeLogs();
                updateConnectionStatus("Reintentando RTSP (error " + what + "/" + extra + ")...", "#FF5252");
                mMainHandler.postDelayed(mReconnectRunnable, 2000);
            });

            mMediaPlayer.setOnRtcpSrDataListener((mp, data) -> handleMicroscopeHardwareButton(data));

            // Follow exact call order from IjkVideoView.openVideo(): setDataSource -> setSurface -> prepareAsync
            mMediaPlayer.setDataSource(mActiveRtspUrl);
            mMediaPlayer.setSurface(mSurface);
            mMediaPlayer.prepareAsync();

            mMainHandler.postDelayed(mConnectionWatchdogRunnable, 6000);
        } catch (Exception e) {
            mIsConnecting = false;
            mIsConnected = false;
            appendDiag("Excepción al iniciar IjkMediaPlayer: " + e.getMessage());
            updateConnectionStatus("Error RTSP: " + e.getMessage(), "#FF5252");
            mMainHandler.postDelayed(mReconnectRunnable, 2500);
        }
    }

    /**
     * Decodes RTCP SR packets sent by the physical buttons on the FlyScope / OMSee microscope body.
     * Signature: 0x0F (15), 0x5A (90), 0x1E (30), 0x69 (105)
     */
    private void handleMicroscopeHardwareButton(byte[] data) {
        if (data == null || data.length < 7) return;
        if ((data[0] & 0xFF) != 0x0F ||
            (data[1] & 0xFF) != 0x5A ||
            (data[2] & 0xFF) != 0x1E ||
            (data[3] & 0xFF) != 0x69) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - mLastHwCmdTimeMs < 450L) {
            return;
        }
        mLastHwCmdTimeMs = now;
        int actionClass = data[4] & 0xFF;
        int cmdValue = data[6] & 0xFF;

        switch (actionClass) {
            case 1: // HW_ACTION_CLASS_TAKE_PHOTO
                if (cmdValue == 1) {
                    triggerPhotoCapture();
                } else {
                    toggleVideoRecording();
                }
                break;
            case 2: // HW_ACTION_CLASS_RECORD_VIDEO
            case 3: // HW_ACTION_CLASS_RECORD_VIDEO2
                if (cmdValue == 0) {
                    toggleVideoRecording();
                }
                break;
            case 4: // HW_ACTION_CLASS_PHOTO_ZOOM_IN
                mZoomFactor = Math.min(4.0f, mZoomFactor + 1.0f);
                updateZoomLabel();
                applyTextureViewTransform();
                break;
            case 5: // HW_ACTION_CLASS_PHOTO_ZOOM_OUT
                mZoomFactor = Math.max(1.0f, mZoomFactor - 1.0f);
                updateZoomLabel();
                applyTextureViewTransform();
                break;
            case 10: // HW_ACTION_CLASS_AI
                mShowCircleMask = !mShowCircleMask;
                mScopeOverlayView.setVisibility(mShowCircleMask ? View.VISIBLE : View.GONE);
                break;
            default:
                break;
        }
    }

    private void triggerPhotoCapture() {
        long now = System.currentTimeMillis();
        if (now - mLastPhotoCaptureTimeMs < 400L) {
            return;
        }
        mLastPhotoCaptureTimeMs = now;
        if (mShutterSound != null) {
            mShutterSound.play(MediaActionSound.SHUTTER_CLICK);
        }
        // Shutter flash animation
        mFlashOverlay.setAlpha(0.75f);
        mFlashOverlay.animate().alpha(0f).setDuration(220).start();

        mCaptureEngine.takePhoto(
                mTextureView,
                mVideoWidth,
                mVideoHeight,
                mRotationDegrees,
                mMirrorHorizontal,
                mZoomFactor
        );
    }

    private void toggleVideoRecording() {
        if (mCaptureEngine.isRecording()) {
            mCaptureEngine.stopRecording();
        } else {
            mCaptureEngine.startRecording(
                    mTextureView,
                    mVideoWidth,
                    mVideoHeight,
                    mRotationDegrees,
                    mMirrorHorizontal,
                    mZoomFactor
            );
        }
    }

    private void cycleZoom() {
        if (mZoomFactor < 1.5f) {
            mZoomFactor = 1.5f;
        } else if (mZoomFactor < 2.0f) {
            mZoomFactor = 2.0f;
        } else if (mZoomFactor < 3.0f) {
            mZoomFactor = 3.0f;
        } else if (mZoomFactor < 4.0f) {
            mZoomFactor = 4.0f;
        } else {
            mZoomFactor = 1.0f;
        }
        updateZoomLabel();
        applyTextureViewTransform();
    }

    private void updateZoomLabel() {
        mZoomBtn.setText(String.format(Locale.US, "🔍 %.1fx", mZoomFactor));
    }

    /**
     * Applies rotation, mirror, aspect-ratio correction, and digital zoom to the live TextureView
     * using exact 2D affine mapping from the unrotated TextureView quad [-W/2, W/2] x [-H/2, H/2]
     * to the target rotated/aspect-fitted rectangle [-targetW/2, targetW/2] x [-targetH/2, targetH/2].
     */
    private void applyTextureViewTransform() {
        int viewW = mTextureView.getWidth();
        int viewH = mTextureView.getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        int srcW = mVideoWidth > 0 ? mVideoWidth : 640;
        int srcH = mVideoHeight > 0 ? mVideoHeight : 480;

        boolean swapped = (mRotationDegrees % 180 != 0);
        float effW = swapped ? srcH : srcW;
        float effH = swapped ? srcW : srcH;

        float viewAspect = (float) viewW / viewH;
        float videoAspect = effW / effH;

        // Fit inside the screen while preserving the exact 4:3 / 3:4 microscope aspect ratio
        float targetW;
        float targetH;
        if (videoAspect > viewAspect) {
            targetW = viewW;
            targetH = viewW / videoAspect;
        } else {
            targetH = viewH;
            targetW = viewH * videoAspect;
        }

        // Before rotation, TextureView stretches the video buffer to (viewW, viewH).
        // If we rotate by 90/270 deg, the pre-rotated width (viewW) becomes the vertical axis (targetH),
        // and the pre-rotated height (viewH) becomes the horizontal axis (targetW).
        float preScaleX;
        float preScaleY;
        if (swapped) {
            preScaleX = targetH / (float) viewW;
            preScaleY = targetW / (float) viewH;
        } else {
            preScaleX = targetW / (float) viewW;
            preScaleY = targetH / (float) viewH;
        }

        float centerX = viewW / 2f;
        float centerY = viewH / 2f;

        Matrix matrix = new Matrix();
        // 1. Scale pre-rotated quad so its post-rotation dimensions are (targetW, targetH)
        matrix.setScale(preScaleX * mZoomFactor, preScaleY * mZoomFactor, centerX, centerY);
        // 2. Rotate around screen center
        matrix.postRotate(mRotationDegrees, centerX, centerY);
        // 3. Apply horizontal mirror in screen space if requested
        if (mMirrorHorizontal) {
            matrix.postScale(-1f, 1f, centerX, centerY);
        }

        mTextureView.setTransform(matrix);
    }

    private void updateConnectionStatus(String text, String dotHexColor) {
        mMainHandler.post(() -> {
            mStatusLabel.setText(" " + text);
            mStatusDot.setTextColor(Color.parseColor(dotHexColor));
        });
    }

    private void openGallery() {
        Intent intent = new Intent(this, GalleryActivity.class);
        startActivity(intent);
    }

    private void releasePlayer() {
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.reset();
            } catch (Exception ignored) {
            }
            try {
                mMediaPlayer.release();
            } catch (Exception ignored) {
            }
            mMediaPlayer = null;
        }
    }

    // TextureView.SurfaceTextureListener callbacks
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mSurface = new Surface(surface);
        applyTextureViewTransform();
        bindToMicroscopeWifiNetwork();
        startRtspStream();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        applyTextureViewTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mMainHandler.removeCallbacks(mReconnectRunnable);
        mMainHandler.removeCallbacks(mConnectionWatchdogRunnable);
        releasePlayer();
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    // MediaCaptureEngine.CaptureCallback implementation
    @Override
    public void onPhotoSaved(File file, Bitmap preview) {
        mMainHandler.post(() -> {
            mLastThumbView.setImageBitmap(preview);
            Toast.makeText(this, "Foto guardada completa: " + file.getName(), Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onPhotoError(String message) {
        mMainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onVideoStarted() {
        mMainHandler.post(() -> {
            mTimerBadge.setVisibility(View.VISIBLE);
            mTimerBadge.setText("● REC 00:00");
            mVideoBtn.setText("⏹ PARAR");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#B71C1C"));
            bg.setCornerRadius(dp(25));
            mVideoBtn.setBackground(bg);
        });
    }

    @Override
    public void onVideoTimerTick(long elapsedMillis) {
        mMainHandler.post(() -> {
            long totalSec = elapsedMillis / 1000;
            long mins = totalSec / 60;
            long secs = totalSec % 60;
            mTimerBadge.setText(String.format(Locale.US, "● REC %02d:%02d", mins, secs));
        });
    }

    @Override
    public void onVideoSaved(File file) {
        mMainHandler.post(() -> {
            mTimerBadge.setVisibility(View.GONE);
            mVideoBtn.setText("⏺ VÍDEO");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#D81B60"));
            bg.setCornerRadius(dp(25));
            mVideoBtn.setBackground(bg);
            Toast.makeText(this, "Vídeo MP4 guardado completo: " + file.getName(), Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onVideoError(String message) {
        mMainHandler.post(() -> {
            mTimerBadge.setVisibility(View.GONE);
            mVideoBtn.setText("⏺ VÍDEO");
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindToMicroscopeWifiNetwork();
        if (!mIsConnected && !mIsConnecting && mSurface != null) {
            startRtspStream();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mCaptureEngine != null && mCaptureEngine.isRecording()) {
            mCaptureEngine.stopRecording();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mMainHandler.removeCallbacksAndMessages(null);
        releasePlayer();
        try {
            IjkMediaPlayer.native_profileEnd();
        } catch (Exception ignored) {
        }
        if (mShutterSound != null) {
            mShutterSound.release();
        }
        if (mConnectivityManager != null && mWifiNetworkCallback != null) {
            try {
                mConnectivityManager.bindProcessToNetwork(null);
                mConnectivityManager.unregisterNetworkCallback(mWifiNetworkCallback);
            } catch (Exception ignored) {
            }
        }
    }

    private void requestPermissionsIfNeeded() {
        List<String> perms = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), 101);
        }
    }

    private Button createHudButton(String text, String hexColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        return b;
    }

    private Button createPrimaryButton(String text, String hexColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(dp(25));
        b.setBackground(bg);
        return b;
    }

    private LinearLayout.LayoutParams createToolWeightParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /**
     * Draws a dark ocular vignette mask around the center circle when AI/Ocular mode is toggled.
     */
    private static class ScopeOverlayView extends View {
        private final Paint mMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mClipPath = new Path();

        public ScopeOverlayView(Context context) {
            super(context);
            mMaskPaint.setColor(Color.parseColor("#CC000000"));
            mMaskPaint.setStyle(Paint.Style.FILL);

            mRingPaint.setColor(Color.parseColor("#8000E676"));
            mRingPaint.setStyle(Paint.Style.STROKE);
            mRingPaint.setStrokeWidth(4f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float cx = w / 2f;
            float cy = h / 2f;
            float radius = Math.min(w, h) * 0.42f;

            mClipPath.reset();
            mClipPath.addRect(0, 0, w, h, Path.Direction.CW);
            mClipPath.addCircle(cx, cy, radius, Path.Direction.CCW);
            mClipPath.setFillType(Path.FillType.EVEN_ODD);

            canvas.drawPath(mClipPath, mMaskPaint);
            canvas.drawCircle(cx, cy, radius, mRingPaint);
        }
    }
}
