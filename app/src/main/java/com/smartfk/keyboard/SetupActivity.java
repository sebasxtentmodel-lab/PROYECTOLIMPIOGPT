package com.smartfk.keyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Only the one-time setup page has controls; the keyboard view has none. */
public final class SetupActivity extends Activity {
    private TextView info;
    private OfflineTranslation models;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28,40,28,24);
        root.setGravity(Gravity.TOP);
        root.setBackgroundColor(0xFFFFFFFF);
        info = new TextView(this);
        info.setTextColor(0xFF111111);
        info.setTextSize(17);
        info.setText("SmartFk alfa\n\nActiva el micrófono, descarga una vez los dos modelos de traducción y selecciona SmartFk como teclado.\n\nIMPORTANTE: esta versión alfa no es 100 % offline desde la primera instalación: ML Kit necesita descargar los modelos por primera vez. Después, la traducción se realiza localmente. Las voces TTS dependen de las voces sin conexión instaladas en Android.");
        root.addView(info,new LinearLayout.LayoutParams(-1,-2));
        Button permission = button("Conceder acceso al micrófono",root);
        permission.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},30);
            else message("Permiso de micrófono concedido.");
        });
        Button modelsButton = button("Preparar traducción para uso sin conexión",root);
        modelsButton.setOnClickListener(v -> {
            if (models == null) models = new OfflineTranslation(this);
            message("Preparando modelos de traducción...");
            models.prepare(new OfflineTranslation.Callback() {
                @Override public void onSuccess(String text) { message(text); }
                @Override public void onFailure(String err) { message("No se pudo preparar: "+err); }
            });
        });
        Button settings = button("Habilitar SmartFk en Android",root);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        Button picker = button("Seleccionar SmartFk",root);
        picker.setOnClickListener(v -> {
            InputMethodManager im=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showInputMethodPicker();
        });
        setContentView(root);
    }

    private Button button(String label,LinearLayout parent) {
        Button b = new Button(this);
        b.setText(label);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin=18;
        parent.addView(b,p);
        return b;
    }
    private void message(String msg) { runOnUiThread(() -> info.append("\n\n"+msg)); }
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] results) {
        super.onRequestPermissionsResult(code,p,results);
        if (code==30) message(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED
                ? "Micrófono autorizado." : "Micrófono no autorizado.");
    }
    @Override public void onDestroy() {
        if (models != null) models.close();
        super.onDestroy();
    }
}
