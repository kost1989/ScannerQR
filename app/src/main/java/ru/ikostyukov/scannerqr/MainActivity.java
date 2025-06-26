package ru.ikostyukov.scannerqr;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "QRScanner";
    private androidx.camera.view.PreviewView previewView;
    private TextView resultText;
    private TextView permissionText;
    private LinearLayout buttonLayout;
    private Button btnCopy;
    private Button btnRescan;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private ProcessCameraProvider cameraProvider;
    private final AtomicBoolean shouldAnalyze = new AtomicBoolean(true);
    private String lastResult = "";

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    permissionText.setVisibility(View.GONE);
                    startCamera();
                } else {
                    permissionText.setVisibility(View.VISIBLE);
                    previewView.setVisibility(View.GONE);
                    resultText.setVisibility(View.GONE);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            // Инициализация UI компонентов
            previewView = findViewById(R.id.preview_view);
            resultText = findViewById(R.id.result_text);
            permissionText = findViewById(R.id.permission_text);
            buttonLayout = findViewById(R.id.button_layout);
            btnCopy = findViewById(R.id.btn_copy);
            btnRescan = findViewById(R.id.btn_rescan);

            // Инициализация сканера QR-кодов
            BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build();
            barcodeScanner = BarcodeScanning.getClient(options);

            // Инициализация исполнителя камеры
            cameraExecutor = Executors.newSingleThreadExecutor();

            // Настройка кнопок
            btnCopy.setOnClickListener(v -> copyToClipboard());
            btnRescan.setOnClickListener(v -> restartScanning());

            // Проверка разрешений камеры
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionText.setVisibility(View.GONE);
                startCamera();
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            }
        } catch (Exception e) {
            Log.e(TAG, "Critical error in onCreate", e);
            Toast.makeText(this, "App initialization failed", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        try {
            // Отвязать все предыдущие use cases
            cameraProvider.unbindAll();

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                if (!shouldAnalyze.get() || imageProxy.getImage() == null) {
                    imageProxy.close();
                    return;
                }
                analyzeImage(imageProxy);
            });

            // Привязка к жизненному циклу
            cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
            );
        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed", e);
        }
    }

    private void analyzeImage(ImageProxy imageProxy) {
        try {
            InputImage image = InputImage.fromMediaImage(
                    imageProxy.getImage(),
                    imageProxy.getImageInfo().getRotationDegrees()
            );

            barcodeScanner.process(image)
                    .addOnSuccessListener(barcodes -> {
                        if (!barcodes.isEmpty()) {
                            Barcode barcode = barcodes.get(0);
                            String rawValue = barcode.getRawValue();
                            if (rawValue != null) {
                                shouldAnalyze.set(false);
                                lastResult = rawValue;
                                runOnUiThread(() -> {
                                    displayResult(rawValue);
                                    showButtons();
                                });
                            }
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "Barcode scanning failed", e))
                    .addOnCompleteListener(task -> imageProxy.close());
        } catch (Exception e) {
            Log.e(TAG, "Image analysis error", e);
            imageProxy.close();
        }
    }

    private void displayResult(String text) {
        if (text.startsWith("http://") || text.startsWith("https://")) {
            makeTextClickable(text);
        } else {
            resultText.setText(text);
        }
    }

    private void showButtons() {
        buttonLayout.setVisibility(View.VISIBLE);
    }

    private void hideButtons() {
        buttonLayout.setVisibility(View.GONE);
    }

    private void copyToClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("QR Result", lastResult);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Copy failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartScanning() {
        shouldAnalyze.set(true);
        lastResult = "";
        resultText.setText(R.string.scanning_hint);
        hideButtons();
        bindCameraUseCases();
    }

    @Override
    protected void onResume() {
        super.onResume();
        shouldAnalyze.set(true);

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {

            if (cameraProvider != null) {
                bindCameraUseCases();
            } else {
                startCamera();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        shouldAnalyze.set(false);
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseAllResources();
        finishAndRemoveTask();
    }

    private void releaseAllResources() {
        // 1. Остановка анализа
        shouldAnalyze.set(false);

        // 2. Освобождение камеры
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
                cameraProvider = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera", e);
            }
        }

        // 3. Закрытие сканера
        if (barcodeScanner != null) {
            try {
                barcodeScanner.close();
                barcodeScanner = null;
            } catch (Exception e) {
                Log.e(TAG, "Error closing barcode scanner", e);
            }
        }

        // 4. Остановка исполнителя
        if (cameraExecutor != null) {
            try {
                cameraExecutor.shutdownNow();
                cameraExecutor = null;
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down executor", e);
            }
        }
    }

    private void releaseCamera() {
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera", e);
            }
        }
    }

    private void releaseCameraResources() {
        // Остановка анализа
        shouldAnalyze.set(false);

        // Освобождение камеры
        releaseCamera();

        // Закрытие сканера
        if (barcodeScanner != null) {
            try {
                barcodeScanner.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing barcode scanner", e);
            }
        }

        // Завершение исполнителя
        if (cameraExecutor != null) {
            try {
                cameraExecutor.shutdownNow();
            } catch (Exception e) {
                Log.e(TAG, "Error shutting down executor", e);
            }
        }
    }

    private void makeTextClickable(String url) {
        SpannableString spannable = new SpannableString(url);
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                openWebPage(url);
            }
        };
        spannable.setSpan(clickableSpan, 0, url.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        resultText.setText(spannable);
        resultText.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void openWebPage(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Invalid URL: " + url, Toast.LENGTH_LONG).show();
        }
    }
}