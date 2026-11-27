package com.margrop.wrongnotebook;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.herohan.uvcapp.CameraException;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.herohan.uvcapp.ImageCapture;
import com.herohan.uvcapp.ImageCaptureConfig;
import com.serenegiant.usb.Size;
import com.serenegiant.utils.UVCUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 通过 USB Host / UVC 获取真实相机帧，不依赖系统 Camera HAL。 */
public class UsbCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    static final String EXTRA_DEVICE_NAME = "usbDeviceName";
    static final String EXTRA_OUTPUT_PATH = "usbOutputPath";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> fail(R.string.camera_usb_timeout);
    private ICameraHelper helper;
    private String deviceName;
    private File output;
    private TextureView preview;
    private TextView status;
    private Button shutter;
    private Button resolution;
    private SurfaceTexture attachedSurface;
    private boolean selected;
    private boolean opened;
    private boolean ready;
    private boolean capturing;
    private boolean completed;
    private int generation;
    private Size requestedSize;
    private Size actualSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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

    @Override
    protected void onStart() {
        super.onStart();
        if (output == null || isFinishing()) return;
        boolean present = false;
        for (UsbDevice device : UsbCameraDevices.list(this)) {
            if (matches(device)) present = true;
        }
        if (!present) {
            fail(R.string.camera_usb_disconnected);
            return;
        }
        try {
            UVCUtils.init(getApplication());
            helper = new CameraHelper();
            ImageCaptureConfig config = helper.getImageCaptureConfig();
            config.setJpegCompressionQuality(100);
            helper.setImageCaptureConfig(config);
            helper.setStateCallback(callback(++generation));
        } catch (RuntimeException | LinkageError e) {
            fail(R.string.camera_usb_failed);
        }
    }

    private boolean matches(UsbDevice device) {
        return device != null && deviceName.equals(device.getDeviceName());
    }

    private boolean active(int token) {
        return token == generation && helper != null && !isFinishing() && !completed;
    }

    private ICameraHelper.StateCallback callback(int token) {
        return new ICameraHelper.StateCallback() {
            @Override public void onAttach(UsbDevice device) {
                if (!active(token) || !matches(device) || selected) return;
                selected = true;
                helper.selectDevice(device);
            }

            @Override public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
                if (!active(token) || !matches(device) || opened) return;
                armTimeout();
                helper.openCamera();
            }

            @Override public void onCameraOpen(UsbDevice device) {
                if (!active(token) || !matches(device)) return;
                opened = true;
                attachPreview();
                helper.startPreview();
                armTimeout();
            }

            @Override public void onCameraClose(UsbDevice device) {
                if (active(token) && matches(device)) fail(R.string.camera_usb_failed);
            }

            @Override public void onDeviceClose(UsbDevice device) {
                if (active(token) && matches(device)) fail(R.string.camera_usb_disconnected);
            }

            @Override public void onDetach(UsbDevice device) {
                if (active(token) && matches(device)) fail(R.string.camera_usb_disconnected);
            }

            @Override public void onCancel(UsbDevice device) {
                if (active(token) && matches(device)) fail(R.string.camera_permission_denied);
            }

            @Override public void onError(UsbDevice device, CameraException error) {
                if (active(token) && matches(device)) fail(R.string.camera_usb_failed);
            }
        };
    }

    private void armTimeout() {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 15000);
    }

    private void attachPreview() {
        if (helper == null || !opened || !preview.isAvailable()) return;
        SurfaceTexture surface = preview.getSurfaceTexture();
        if (surface == null || surface == attachedSurface) return;
        attachedSurface = surface;
        helper.addSurface(surface, false);
    }

    private void setWaiting() {
        ready = false;
        shutter.setEnabled(false);
        resolution.setEnabled(false);
        status.setText(R.string.camera_usb_connecting);
    }

    private void updateAspect() {
        if (actualSize == null || preview.getWidth() == 0 || preview.getHeight() == 0) return;
        float imageRatio = (float) actualSize.width / actualSize.height;
        float viewRatio = (float) preview.getWidth() / preview.getHeight();
        Matrix matrix = new Matrix();
        matrix.setScale(Math.min(1f, imageRatio / viewRatio), Math.min(1f, viewRatio / imageRatio),
                preview.getWidth() / 2f, preview.getHeight() / 2f);
        preview.setTransform(matrix);
    }

    private boolean sameSize(Size a, Size b) {
        return a != null && b != null && a.width == b.width && a.height == b.height
                && a.type == b.type && a.fps == b.fps;
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        attachPreview();
        if (opened) armTimeout();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        updateAspect();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (helper != null && attachedSurface == surface) helper.removeSurface(surface);
        attachedSurface = null;
        if (shutter != null) setWaiting();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // 只有收到真实预览帧才允许拍照，避免返回黑图。
        if (helper == null || !opened || ready || capturing || completed || isFinishing()) return;
        Size size = helper.getPreviewSize();
        if (size == null || (requestedSize != null && !sameSize(requestedSize, size))) return;
        actualSize = size;
        requestedSize = null;
        ready = true;
        handler.removeCallbacks(timeout);
        updateAspect();
        status.setText(getString(R.string.camera_usb_ready, size.width, size.height));
        shutter.setEnabled(true);
        resolution.setEnabled(true);
    }

    private void chooseResolution() {
        if (!ready || helper == null || capturing) return;
        List<Size> supported = helper.getSupportedSizeList();
        if (supported == null || supported.isEmpty()) return;
        List<Size> sizes = new ArrayList<>(supported);
        sizes.sort(Comparator.comparingLong((Size size) -> (long) size.width * size.height)
                .thenComparingInt(size -> size.fps).reversed());
        List<Size> unique = new ArrayList<>();
        for (Size size : sizes) {
            boolean duplicate = false;
            for (Size previous : unique) if (sameSize(previous, size)) duplicate = true;
            if (!duplicate) unique.add(size);
        }
        String[] labels = new String[unique.size()];
        int current = -1;
        for (int i = 0; i < unique.size(); i++) {
            Size size = unique.get(i);
            labels[i] = getString(R.string.camera_resolution_option, size.width, size.height, size.fps, size.type);
            if (sameSize(size, actualSize)) current = i;
        }
        new AlertDialog.Builder(this).setTitle(R.string.camera_resolution)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (helper == null || !ready || sameSize(unique.get(which), actualSize)) return;
                    requestedSize = unique.get(which);
                    setWaiting();
                    helper.stopPreview();
                    helper.setPreviewSize(requestedSize);
                    helper.startPreview();
                    armTimeout();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void takePhoto() {
        if (helper == null || !ready || capturing || output == null) return;
        capturing = true;
        shutter.setEnabled(false);
        resolution.setEnabled(false);
        status.setText(R.string.camera_usb_capturing);
        armTimeout();
        final int token = generation;
        helper.takePicture(new ImageCapture.OutputFileOptions.Builder(output).build(),
                new ImageCapture.OnImageCaptureCallback() {
                    @Override public void onImageSaved(ImageCapture.OutputFileResults result) {
                        runOnUiThread(() -> {
                            if (!active(token)) return;
                            if (!output.isFile() || output.length() == 0) {
                                fail(R.string.camera_usb_failed);
                                return;
                            }
                            completed = true;
                            handler.removeCallbacks(timeout);
                            setResult(RESULT_OK);
                            finish();
                        });
                    }

                    @Override public void onError(int code, String message, Throwable cause) {
                        runOnUiThread(() -> {
                            if (active(token)) fail(R.string.camera_usb_failed);
                        });
                    }
                });
    }

    private void fail(int message) {
        if (isFinishing() || completed) return;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(timeout);
        generation++;
        ICameraHelper old = helper;
        helper = null;
        selected = false;
        opened = false;
        capturing = false;
        requestedSize = null;
        actualSize = null;
        if (old != null) {
            if (attachedSurface != null) old.removeSurface(attachedSurface);
            old.release();
        }
        attachedSurface = null;
        if (shutter != null) setWaiting();
        super.onStop();
    }
}
