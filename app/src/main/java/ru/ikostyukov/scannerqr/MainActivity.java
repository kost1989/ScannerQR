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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "QRScanner";
    private androidx.camera.view.PreviewView previewView;
    private TextView resultText;
    private LinearLayout buttonLayout;
    private Button btnCopy;
    private Button btnRescan;
    private Executor cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private boolean isScanning = true;
    private String lastResult = "";

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(MainActivity.this, "Camera permission is required", Toast.LENGTH_LONG).show();
                    resultText.setText("Camera permission required");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);
            Log.d(TAG, "onCreate started");

            // Инициализация UI компонентов
            previewView = findViewById(R.id.preview_view);
            resultText = findViewById(R.id.result_text);
            buttonLayout = findViewById(R.id.button_layout);
            btnCopy = findViewById(R.id.btn_copy);
            btnRescan = findViewById(R.id.btn_rescan);

            // Проверка инициализации кнопок
            if (btnCopy == null || btnRescan == null) {
                Log.e(TAG, "Buttons not found in layout");
                Toast.makeText(this, "UI initialization error", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

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
                startCamera();
            } else {
                requestPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            }

            Log.d(TAG, "onCreate completed");
        } catch (Exception e) {
            Log.e(TAG, "Critical error in onCreate", e);
            Toast.makeText(this, "App initialization failed", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startCamera() {
        Log.d(TAG, "Starting camera");
        try {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                    ProcessCameraProvider.getInstance(this);

            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    bindCameraUseCases(cameraProvider);
                } catch (Exception e) {
                    Log.e(TAG, "Camera initialization failed", e);
                    Toast.makeText(this, "Camera init failed", Toast.LENGTH_LONG).show();
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            Log.e(TAG, "Error starting camera", e);
        }
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        Log.d(TAG, "Binding camera use cases");
        try {
            // Отвязать все предыдущие use cases
            cameraProvider.unbindAll();

            // Выбор камеры (задняя)
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            // Настройка предпросмотра
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            // Настройка анализа изображения
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setTargetResolution(new Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

            // Привязка к жизненному циклу
            cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
            );

            Log.d(TAG, "Camera successfully bound");
        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed", e);
        }
    }

    private void analyzeImage(ImageProxy imageProxy) {
        if (!isScanning || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

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
                                isScanning = false;
                                lastResult = rawValue;
                                runOnUiThread(() -> {
                                    displayResult(rawValue);
                                    showButtons();
                                });
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Barcode scanning failed", e);
                    })
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
        isScanning = true;
        lastResult = "";
        resultText.setText(R.string.scanning_hint);
        hideButtons();
        startCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera();
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