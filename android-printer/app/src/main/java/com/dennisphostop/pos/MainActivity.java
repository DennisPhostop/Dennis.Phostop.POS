package com.dennisphostop.pos;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String POS_URL = "https://dennisphostop.github.io/Dennis.Phostop.POS/";
    private static final int BLUETOOTH_PERMISSION_REQUEST = 4102;
    private static final int STORAGE_PERMISSION_REQUEST = 4103;
    private WebView webView;
    private PrinterBridge printerBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestBluetoothPermissions();
        requestStoragePermission();

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);

        printerBridge = new PrinterBridge(this, webView);
        webView.addJavascriptInterface(printerBridge, "AndroidPrinter");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("dennisphostop.github.io".equalsIgnoreCase(uri.getHost())) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl(POS_URL);
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
            }, BLUETOOTH_PERMISSION_REQUEST);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, BLUETOOTH_PERMISSION_REQUEST);
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (printerBridge != null) printerBridge.close();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public static class PrinterBridge {
        private static final UUID SERIAL_PORT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
        private final Activity activity;
        private final Context context;
        private final WebView webView;
        private final BluetoothAdapter adapter;
        private BluetoothSocket socket;
        private OutputStream outputStream;
        private String connectedName = "";

        PrinterBridge(Activity activity, WebView webView) {
            this.activity = activity;
            this.context = activity;
            this.webView = webView;
            BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
            this.adapter = manager == null ? null : manager.getAdapter();
        }

        private boolean hasConnectPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        private String success(String message) {
            try {
                return new JSONObject().put("ok", true).put("message", message).put("name", connectedName).toString();
            } catch (Exception error) {
                return "{\"ok\":true}";
            }
        }

        private String failure(String message) {
            try {
                return new JSONObject().put("ok", false).put("message", message).toString();
            } catch (Exception error) {
                return "{\"ok\":false,\"message\":\"Printer error\"}";
            }
        }

        @JavascriptInterface
        public String getPairedDevices() {
            if (adapter == null) return failure("Bluetooth is not available on this tablet.");
            if (!adapter.isEnabled()) return failure("Turn on Bluetooth in Redmi settings first.");
            if (!hasConnectPermission()) return failure("Allow Nearby devices permission, then reopen Printer Setup.");

            try {
                JSONArray devices = new JSONArray();
                Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
                for (BluetoothDevice device : bondedDevices) {
                    JSONObject item = new JSONObject();
                    item.put("name", device.getName() == null ? "Bluetooth device" : device.getName());
                    item.put("address", device.getAddress());
                    devices.put(item);
                }
                return new JSONObject().put("ok", true).put("devices", devices).toString();
            } catch (Exception error) {
                return failure("Could not read paired Bluetooth devices: " + error.getMessage());
            }
        }

        @JavascriptInterface
        public synchronized String connect(String address) {
            if (adapter == null) return failure("Bluetooth is not available on this tablet.");
            if (!adapter.isEnabled()) return failure("Turn on Bluetooth in Redmi settings first.");
            if (!hasConnectPermission()) return failure("Allow Nearby devices permission, then try again.");
            if (address == null || address.trim().isEmpty()) return failure("Select MRBOSS E2000 first.");

            close();
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(address.trim());
                socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_UUID);
                socket.connect();
            } catch (Exception secureError) {
                close();
                try {
                    device = adapter.getRemoteDevice(address.trim());
                    socket = device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT_UUID);
                    socket.connect();
                } catch (Exception insecureError) {
                    close();
                    return failure("Cannot connect to MRBOSS E2000. Confirm it is paired and not connected to another device.");
                }
            }

            try {
                outputStream = socket.getOutputStream();
                connectedName = device.getName() == null ? "MRBOSS E2000" : device.getName();
                return success("Connected");
            } catch (IOException error) {
                close();
                return failure("Connected, but the printer output channel could not be opened.");
            }
        }

        @JavascriptInterface
        public synchronized String printBase64(String payload) {
            if (socket == null || !socket.isConnected() || outputStream == null) {
                return failure("Printer is not connected. Open Setup > Printer Setup and connect MRBOSS E2000.");
            }
            try {
                byte[] bytes = Base64.decode(payload, Base64.DEFAULT);
                outputStream.write(bytes);
                outputStream.flush();
                return success("Printed");
            } catch (Exception error) {
                close();
                return failure("Printing failed. Reconnect MRBOSS E2000 and try again.");
            }
        }

        @JavascriptInterface
        public String printPage(String requestedJobName) {
            final String jobName = requestedJobName == null || requestedJobName.trim().isEmpty()
                    ? "Pho Stop POS Report"
                    : requestedJobName.trim();
            final PrintManager printManager = (PrintManager) activity.getSystemService(Context.PRINT_SERVICE);
            if (printManager == null) return failure("Android print service is not available on this tablet.");
            try {
                activity.runOnUiThread(() -> {
                    PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
                    printManager.print(jobName, adapter, new PrintAttributes.Builder().build());
                });
                return success("Print dialog opened.");
            } catch (Exception error) {
                return failure(error.getMessage() == null ? "Unable to open the print dialog." : error.getMessage());
            }
        }

        private String safeFileName(String value) {
            String name = value == null ? "report.xls" : value.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
            return name.isEmpty() ? "report.xls" : name;
        }

        @JavascriptInterface
        public String saveBase64File(String requestedName, String mimeType, String base64Data) {
            final String fileName = safeFileName(requestedName);
            final String resolvedMimeType = mimeType == null || mimeType.trim().isEmpty()
                    ? "application/octet-stream"
                    : mimeType.trim();
            try {
                byte[] data = Base64.decode(base64Data, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(MediaStore.Downloads.MIME_TYPE, resolvedMimeType);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pho Stop POS");
                    values.put(MediaStore.Downloads.IS_PENDING, 1);

                    Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) return failure("Could not create the report file in Downloads.");
                    try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
                        if (stream == null) throw new IOException("Could not open the report file.");
                        stream.write(data);
                    } catch (Exception error) {
                        context.getContentResolver().delete(uri, null, null);
                        throw error;
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    context.getContentResolver().update(uri, values, null, null);
                } else {
                    File directory = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "Pho Stop POS"
                    );
                    if (!directory.exists() && !directory.mkdirs()) {
                        return failure("Could not create Downloads/Pho Stop POS.");
                    }
                    try (OutputStream stream = new FileOutputStream(new File(directory, fileName))) {
                        stream.write(data);
                    }
                }
                return success("Saved to Downloads/Pho Stop POS/" + fileName);
            } catch (Exception error) {
                return failure(error.getMessage() == null ? "Excel download failed." : error.getMessage());
            }
        }

        @JavascriptInterface
        public synchronized String disconnect() {
            close();
            return success("Disconnected");
        }

        synchronized void close() {
            try {
                if (outputStream != null) outputStream.close();
            } catch (IOException ignored) {
            }
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
            outputStream = null;
            socket = null;
            connectedName = "";
        }
    }
}
