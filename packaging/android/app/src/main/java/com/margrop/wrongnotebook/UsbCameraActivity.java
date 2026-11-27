package com.margrop.wrongnotebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.jiangdg.usb.USBMonitor;
import com.jiangdg.utils.Size;
import com.jiangdg.uvc.UVCCamera;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** 仅使用 AUSBC 的底层 UVC 采集模块，不依赖录像、音频或 OpenGL 渲染框架。 */
public class UsbCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    static final String EXTRA_DEVICE_NAME = "usbDeviceName";
    static final String EXTRA_OUTPUT_PATH = "usbOutputPath";
    private static final String TAG = "UsbCameraActivity";
    private static final int TIMEOUT_MS = 15000;
    // USBMonitor 是进程级单例；所有会话的打开、停止和销毁必须串行。
    private static final ExecutorService CAMERA_WORKER =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "wrong-notebook-uvc"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::onTimeout;
    private final String permissionAction = "com.margrop.wrongnotebook.USB_PERMISSION." + UUID.randomUUID();
    private String deviceName;
    private File output;
    private UsbManager usbManager;
    private TextureView preview;
    private TextView status;
    private Button shutter;
    private Button resolution;
    private Session session;
    private List<Mode> modes = new ArrayList<>();
    private Mode selectedMode;
    private PendingIntent permissionIntent;
    private boolean started;
    private boolean receiverRegistered;
    private boolean ready;
    private boolean capturing;
    private boolean completed;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (!matches(device)) return;
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                fail(R.string.camera_usb_disconnected);
            } else if (permissionAction.equals(intent.getAction())) {
                clearPermissionRequest();
                if (!started || isFinishing()) return;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && usbManager.hasPermission(device)) {
                    connectIfReady();
                } else {
                    fail(R.string.camera_permission_denied);
                }
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        String path = getIntent().getStringExtra(EXTRA_OUTPUT_PATH);
        if (deviceName == null || path == null) {
            finish();
            return;
        }
        try {
            output = new File(path).getCanonicalFile();
            File folder = new File(getCacheDir(), "camera").getCanonicalFile();
            if (!folder.equals(output.getParentFile()) || !output.getName().endsWith(".jpg")) {
                finish();
                return;
            }
        } catch (IOException e) {
            finish();
            return;
        }
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        createUi();
    }

    private void createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        SystemBars.applyInsets(root);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        status = new TextView(this);
        status.setPadding(padding, padding, padding, padding);
        status.setText(R.string.camera_usb_connecting);
        root.addView(status);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(padding, padding, padding, padding);
        resolution = new Button(this);
        resolution.setText(R.string.camera_resolution);
        resolution.setEnabled(false);
        resolution.setOnClickListener(v -> chooseResolution());
        shutter = new Button(this);
        shutter.setText(R.string.camera_take_photo);
        shutter.setEnabled(false);
        shutter.setOnClickListener(v -> takePhoto());
        Button cancel = new Button(this);
        cancel.setText(R.string.cancel);
        cancel.setOnClickListener(v -> finish());
        for (Button button : new Button[]{resolution, shutter, cancel}) {
            buttons.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(buttons);
        setContentView(root);
    }

    @Override protected void onStart() {
        super.onStart();
        if (output == null || isFinishing()) return;
        if (usbManager == null) {
            fail(R.string.camera_usb_failed);
            return;
        }
        started = true;
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        connectIfReady();
    }

    private boolean matches(UsbDevice device) {
        return device != null && deviceName != null && deviceName.equals(device.getDeviceName());
    }

    private void connectIfReady() {
        if (!started || isFinishing() || session != null || preview == null || !preview.isAvailable()) return;
        UsbDevice device = null;
        for (UsbDevice candidate : UsbCameraDevices.list(this)) {
            if (matches(candidate)) {
                device = candidate;
                break;
            }
        }
        if (device == null) {
            fail(R.string.camera_usb_disconnected);
            return;
        }
        if (!usbManager.hasPermission(device)) {
            if (permissionIntent != null) return;
            // 由系统 USB 授权，避免库内部重复注册、轮询或重置设备权限。
            Intent intent = new Intent(permissionAction).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags);
            try {
                usbManager.requestPermission(device, permissionIntent);
            } catch (RuntimeException e) {
                Log.e(TAG, "USB 授权失败", e);
                clearPermissionRequest();
                fail(R.string.camera_permission_denied);
            }
            return;
        }
        SurfaceTexture texture = preview.getSurfaceTexture();
        if (texture == null) return;
        Log.i(TAG, "连接 USB 摄像头：" + UsbCameraDevices.label(device) + " " + device.getDeviceName());
        Session next = new Session(device, texture);
        session = next;
        setWaiting();
        armTimeout();
        CAMERA_WORKER.execute(next::open);
    }

    private void clearPermissionRequest() {
        if (permissionIntent != null) {
            permissionIntent.cancel();
            permissionIntent = null;
        }
    }

    private boolean active(Session owner) {
        return started && !isFinishing() && !completed && session == owner && !owner.closed;
    }

    private void armTimeout() {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, TIMEOUT_MS);
    }

    private void onTimeout() {
        if (capturing) {
            fail(R.string.camera_usb_timeout);
            return;
        }
        ready = false;
        shutter.setEnabled(false);
        resolution.setEnabled(!modes.isEmpty());
        status.setText(R.string.camera_usb_timeout);
        Log.w(TAG, "等待真实视频帧超时：" + selectedMode);
    }

    private void setWaiting() {
        ready = false;
        shutter.setEnabled(false);
        resolution.setEnabled(!modes.isEmpty());
        status.setText(R.string.camera_usb_connecting);
    }

    private void updateAspect() {
        if (selectedMode == null || preview.getWidth() == 0 || preview.getHeight() == 0) return;
        float imageRatio = (float) selectedMode.width / selectedMode.height;
        float viewRatio = (float) preview.getWidth() / preview.getHeight();
        Matrix matrix = new Matrix();
        matrix.setScale(Math.min(1f, imageRatio / viewRatio), Math.min(1f, viewRatio / imageRatio),
                preview.getWidth() / 2f, preview.getHeight() / 2f);
        preview.setTransform(matrix);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        connectIfReady();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // 屏幕尺寸只影响显示变换，不作为 UVC 采集缓冲区的尺寸。
        updateAspect();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (session != null && session.texture == surface) stopSession();
        // 原生线程停止并释放 Surface 后再释放 Texture，不能由 TextureView 提前销毁。
        CAMERA_WORKER.execute(surface::release);
        return false;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Surface 更新可能来自黑屏或清屏，不作为摄像头出帧的判断依据。
    }

    private void chooseResolution() {
        Session owner = session;
        if (owner == null || capturing || modes.isEmpty()) return;
        List<Mode> options = new ArrayList<>(modes);
        String[] labels = new String[options.size()];
        int current = -1;
        for (int i = 0; i < options.size(); i++) {
            Mode mode = options.get(i);
            labels[i] = getString(R.string.camera_resolution_option, mode.width, mode.height, mode.formatName());
            if (mode == selectedMode) current = i;
        }
        new AlertDialog.Builder(this).setTitle(R.string.camera_resolution)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (!active(owner) || capturing) return;
                    Mode mode = options.get(which);
                    if (mode == selectedMode && ready) return;
                    selectedMode = mode;
                    setWaiting();
                    resolution.setEnabled(false);
                    updateAspect();
                    armTimeout();
                    int revision = ++owner.revision;
                    CAMERA_WORKER.execute(() -> owner.startMode(mode, revision));
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void takePhoto() {
        Session owner = session;
        if (owner == null || !ready || capturing || output == null) return;
        Stream stream = owner.stream;
        if (stream == null || stream.mode != selectedMode || stream.revision != owner.revision
                || !stream.firstFrame.get()) return;
        capturing = true;
        shutter.setEnabled(false);
        resolution.setEnabled(false);
        status.setText(R.string.camera_usb_capturing);
        armTimeout();
        stream.captureRequested.set(true);
    }

    private void onModes(Session owner, List<Mode> available, Mode initial) {
        if (!active(owner)) return;
        modes = available;
        selectedMode = initial;
        setWaiting();
        resolution.setEnabled(false);
        updateAspect();
        armTimeout();
    }

    private void onFirstFrame(Session owner, Stream stream) {
        if (!active(owner) || owner.stream != stream || stream.revision != owner.revision
                || selectedMode != stream.mode) return;
        ready = true;
        handler.removeCallbacks(timeout);
        updateAspect();
        status.setText(getString(R.string.camera_usb_ready, stream.mode.width, stream.mode.height));
        shutter.setEnabled(true);
        resolution.setEnabled(true);
    }

    private void onModeFailed(Session owner, Mode mode, int revision) {
        if (!active(owner) || selectedMode != mode || revision != owner.revision) return;
        handler.removeCallbacks(timeout);
        ready = false;
        shutter.setEnabled(false);
        resolution.setEnabled(!modes.isEmpty());
        status.setText(R.string.camera_usb_failed);
    }

    private void fail(int message) {
        if (isFinishing() || completed) return;
        handler.removeCallbacks(timeout);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void stopSession() {
        handler.removeCallbacks(timeout);
        Session old = session;
        session = null;
        if (old != null) {
            old.closed = true;
            CAMERA_WORKER.execute(old::close);
        }
        modes = new ArrayList<>();
        selectedMode = null;
        capturing = false;
        if (shutter != null) setWaiting();
    }

    @Override protected void onStop() {
        started = false;
        clearPermissionRequest();
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver);
            receiverRegistered = false;
        }
        stopSession();
        super.onStop();
    }

    /** 库只公布分辨率和传输格式，帧率由原生层在范围内协商，不显示虚假的固定帧率。 */
    private static final class Mode {
        final int width, height, format;
        Mode(int width, int height, int format) {
            this.width = width;
            this.height = height;
            this.format = format;
        }
        long pixels() { return (long) width * height; }
        String formatName() { return format == UVCCamera.FRAME_FORMAT_MJPEG ? "MJPEG" : "YUYV"; }
        @Override public String toString() { return width + "x" + height + " " + formatName(); }
    }

    private static final class Stream {
        final Mode mode;
        final int revision;
        final AtomicBoolean firstFrame = new AtomicBoolean();
        final AtomicBoolean captureRequested = new AtomicBoolean();
        final AtomicLong frames = new AtomicLong();
        Stream(Mode mode, int revision) {
            this.mode = mode;
            this.revision = revision;
        }
    }

    private static List<Mode> readModes(UVCCamera camera) {
        List<Mode> result = new ArrayList<>();
        for (int format : new int[]{UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV}) {
            List<Size> sizes = camera.getSupportedSizeList(format);
            if (sizes == null) continue;
            for (Size size : sizes) {
                // 3.6.0 的原生 YUV 转换按四列处理、Surface 复制按八行展开。
                // 排除不满足其边界要求的模式，防止已知的越界风险。
                if (size.width <= 0 || size.height <= 0 || size.width % 4 != 0 || size.height % 8 != 0
                        || (long) size.width * size.height > Integer.MAX_VALUE / 4) {
                    Log.w(TAG, "跳过不满足原生缓冲区边界的模式：" + size.width + "x" + size.height);
                    continue;
                }
                boolean duplicate = false;
                for (Mode old : result) {
                    if (old.width == size.width && old.height == size.height && old.format == format) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) result.add(new Mode(size.width, size.height, format));
            }
        }
        result.sort(Comparator.comparingLong(Mode::pixels).reversed().thenComparingInt(m -> -m.format));
        return result;
    }

    private static final String MODE_PREFS = "usb_camera_modes";

    private Mode initialMode(UsbDevice device, List<Mode> modes) {
        String saved = getSharedPreferences(MODE_PREFS, MODE_PRIVATE)
                .getString(UsbCameraDevices.key(device), "");
        for (Mode mode : modes) {
            if (mode.toString().equals(saved)) return mode;
        }
        // 试卷扫描默认约 800 万像素 MJPEG；只有 YUYV 时优先低带宽模式。
        // 对其他摄像头使用实际报告的分辨率，而非强制请求固定尺寸。
        return modes.stream().min(Comparator
                .comparingInt((Mode mode) -> mode.format == UVCCamera.FRAME_FORMAT_MJPEG ? 0 : 1)
                .thenComparingLong(mode -> Math.abs(mode.pixels()
                        - (mode.format == UVCCamera.FRAME_FORMAT_MJPEG ? 8_000_000L : 640L * 480)))
                .thenComparingLong(Mode::pixels))
                .orElseThrow(() -> new IllegalStateException("没有可用的 UVC 视频模式"));
    }

    /** 原生接口仅在串行工作线程执行；帧回调不持有原生缓冲区、不调用相机接口。 */
    private final class Session {
        final UsbDevice device;
        final SurfaceTexture texture;
        volatile boolean closed;
        volatile int revision;
        volatile Stream stream;
        private USBMonitor monitor;
        private USBMonitor.UsbControlBlock control;
        private UVCCamera camera;
        private Surface surface;

        Session(UsbDevice device, SurfaceTexture texture) {
            this.device = device;
            this.texture = texture;
        }

        void open() {
            if (closed) return;
            try {
                // 不注册 USBMonitor 的自动广播与设备轮询：Activity 已完成明确的设备授权。
                monitor = USBMonitor.getInstance(getApplicationContext());
                control = monitor.openDevice(device);
                if (control == null || control.getConnection() == null) {
                    throw new IllegalStateException("USB control block 未打开");
                }
                camera = new UVCCamera();
                camera.open(control);
                if (closed) return;
                List<Mode> available = readModes(camera);
                if (available.isEmpty()) throw new IllegalStateException("没有可用的 UVC 视频模式");
                Mode initial = initialMode(device, available);
                Log.i(TAG, "摄像头已打开，支持模式：" + available);
                handler.post(() -> onModes(this, available, initial));
                surface = new Surface(texture);
                startMode(initial, 0);
            } catch (RuntimeException | LinkageError e) {
                Log.e(TAG, "打开 AUSBC 摄像头失败", e);
                handler.post(() -> {
                    if (active(this)) fail(R.string.camera_usb_failed);
                });
            }
        }

        void startMode(Mode mode, int requestRevision) {
            if (closed || revision != requestRevision || camera == null || surface == null) return;
            // stopPreview 会清除原生预览 Surface 与帧回调，因此切换后必须重新绑定。
            // 停止失败时不能继续打开另一个视频流，以免两个原生线程同时使用设备。
            if (stream != null) {
                try {
                    camera.stopPreview();
                    stream = null;
                } catch (RuntimeException | LinkageError e) {
                    Log.e(TAG, "停止上一视频流失败", e);
                    handler.post(() -> {
                        if (active(this)) fail(R.string.camera_usb_failed);
                    });
                    return;
                }
            }
            try {
                if (closed || revision != requestRevision) return;
                if (!surface.isValid()) throw new IllegalStateException("预览 Surface 无效");
                // 复用实测 Multiplex 的协商范围与带宽配置；不遍历未验证的模式。
                camera.setPreviewSize(mode.width, mode.height, 1, 61, mode.format, UVCCamera.DEFAULT_BANDWIDTH);
                texture.setDefaultBufferSize(mode.width, mode.height);
                camera.setPreviewDisplay(surface);
                Stream next = new Stream(mode, requestRevision);
                stream = next;
                // 3.6.0 中该常量对应 uvc_yuyv2iyuv420SP，实际输出 VU 交错的 NV21。
                camera.setFrameCallback(buffer -> onFrame(next, buffer), UVCCamera.PIXEL_FORMAT_YUV420SP);
                Log.i(TAG, "启动预览：" + mode + "，请求 1–61fps，带宽因子 1.0");
                camera.startPreview();
            } catch (RuntimeException | LinkageError e) {
                Log.e(TAG, "启动预览失败：" + mode, e);
                // 保留 stream 标记，下一次切换或关闭会停止可能已启动的原生预览。
                handler.post(() -> onModeFailed(this, mode, requestRevision));
            }
        }

        private void onFrame(Stream owner, ByteBuffer buffer) {
            if (closed || stream != owner || revision != owner.revision || buffer == null) return;
            Mode mode = owner.mode;
            int pixels = mode.width * mode.height;
            int length = pixels + pixels / 2;
            ByteBuffer frame = buffer.duplicate();
            frame.position(0);
            if (frame.remaining() != length) {
                if (owner.frames.get() == 0) Log.w(TAG, "无效帧长度：" + frame.remaining() + "/" + length);
                return;
            }
            long count = owner.frames.incrementAndGet();
            if (owner.firstFrame.compareAndSet(false, true)) {
                int min = 255, max = 0;
                for (int i = 0; i < pixels; i += Math.max(1, pixels / 1024)) {
                    int y = frame.get(i) & 0xff;
                    min = Math.min(min, y);
                    max = Math.max(max, y);
                }
                Log.i(TAG, "收到首个真实 NV21 帧：" + mode + " bytes=" + length + " Y=" + min + ".." + max);
                handler.post(() -> onFirstFrame(this, owner));
            }
            if (count % 100 == 0) Log.d(TAG, "累计帧数：" + count + " " + mode);
            if (owner.captureRequested.compareAndSet(true, false)) {
                // JNI 的 ByteBuffer 在回调返回后立即失效，只在拍照时复制，避免逐帧大块内存分配。
                byte[] data = new byte[length];
                frame.get(data);
                CAMERA_WORKER.execute(() -> savePhoto(owner, data));
            }
        }

        private void savePhoto(Stream owner, byte[] data) {
            if (closed || stream != owner || revision != owner.revision) return;
            Mode mode = owner.mode;
            try (FileOutputStream file = new FileOutputStream(output)) {
                YuvImage image = new YuvImage(data, ImageFormat.NV21, mode.width, mode.height, null);
                if (!image.compressToJpeg(new Rect(0, 0, mode.width, mode.height), 100, file)) {
                    throw new IOException("JPEG 编码失败");
                }
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "保存 JPEG 失败", e);
                output.delete();
                handler.post(() -> {
                    if (active(this)) fail(R.string.camera_usb_failed);
                });
                return;
            }
            if (closed) {
                output.delete();
                return;
            }
            Log.i(TAG, "已保存 JPEG：" + mode + " bytes=" + output.length());
            handler.post(() -> {
                if (!active(this) || stream != owner || revision != owner.revision || !capturing) return;
                if (!output.isFile() || output.length() == 0) {
                    fail(R.string.camera_usb_failed);
                    return;
                }
                // 只记住成功写入 JPEG 的模式，按 VID/PID 区分摄像头型号。
                getSharedPreferences(MODE_PREFS, MODE_PRIVATE).edit()
                        .putString(UsbCameraDevices.key(device), mode.toString()).apply();
                completed = true;
                handler.removeCallbacks(timeout);
                setResult(RESULT_OK);
                finish();
            });
        }

        void close() {
            stream = null;
            // 销毁 camera 会同步停止预览线程并关闭它克隆的 USB connection。
            // 原始 control block 另行关闭：克隆关闭时可能已从 USBMonitor 的映射移除原始对象。
            try {
                if (camera != null) camera.destroy();
            } catch (RuntimeException | LinkageError e) {
                Log.e(TAG, "销毁摄像头失败", e);
            } finally {
                camera = null;
                try {
                    if (control != null) control.close();
                } catch (RuntimeException e) {
                    Log.e(TAG, "关闭 USB control block 失败", e);
                } finally {
                    control = null;
                    try {
                        if (monitor != null) monitor.destroy();
                    } catch (RuntimeException e) {
                        Log.e(TAG, "销毁 USBMonitor 失败", e);
                    } finally {
                        monitor = null;
                        if (surface != null) surface.release();
                        surface = null;
                    }
                }
            }
            Log.i(TAG, "UVC 会话已释放");
        }
    }
}
