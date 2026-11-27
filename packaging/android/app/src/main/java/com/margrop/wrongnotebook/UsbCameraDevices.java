package com.margrop.wrongnotebook;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 仅枚举视频类 USB 设备，不误把键盘、鼠标或 U 盘当成相机。 */
final class UsbCameraDevices {
    private UsbCameraDevices() {}

    static List<UsbDevice> list(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbDevice> result = new ArrayList<>();
        if (manager == null) return result;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (isVideo(device)) result.add(device);
        }
        result.sort(Comparator.comparing(UsbCameraDevices::label).thenComparing(UsbDevice::getDeviceName));
        return result;
    }

    static boolean isVideo(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        }
        return false;
    }

    static String key(UsbDevice device) {
        return String.format(Locale.ROOT, "%04x:%04x", device.getVendorId(), device.getProductId());
    }

    static String label(UsbDevice device) {
        String product = device.getProductName();
        return product == null || product.trim().isEmpty() ? key(device) : product + " (" + key(device) + ")";
    }
}
