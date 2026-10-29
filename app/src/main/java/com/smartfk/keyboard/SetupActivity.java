package com.smartfk.keyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.Set;
import android.widget.TextView;

/** Only the one-time setup page has controls; the keyboard view has none. */
public final class SetupActivity extends Activity {
    private TextView info;
    private OfflineTranslation models;
    private TextToSpeech voiceEngine;

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
        TextView guide=new TextView(this);
        guide.setText("\nIdioma hablado (en automático pueden aparecer errores):");
        guide.setTextColor(0xFF111111);
        guide.setTextSize(17);
        root.addView(guide);
        RadioGroup choices=new RadioGroup(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        String[] keys={"auto","tr","es"};
        String[] labels={"Detectar español o turco automáticamente",
            "Escucho TURCO y quiero ESPAÑOL (más ligero)",
            "Escucho ESPAÑOL y quiero TURCO (más ligero)"};
        String saved=getSharedPreferences("smartfk",MODE_PRIVATE)
            .getString("source_language","auto");
        for (int i=0;i<keys.length;i++) {
            final String language=keys[i];
            RadioButton b=new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(labels[i]);
            b.setTextColor(0xFF111111);
            choices.addView(b);
            b.setChecked(saved.equals(language));
            b.setOnClickListener(v -> {
                getSharedPreferences("smartfk",MODE_PRIVATE).edit()
                    .putString("source_language",language).apply();
                message("Modo seleccionado: "+b.getText()
                    +". Se aplicará al abrir nuevamente el teclado.");
            });
        }
        root.addView(choices);
        voiceEngine=new TextToSpeech(this, status -> {
            if (status!=TextToSpeech.SUCCESS) { message("Motor de voz no disponible.");return; }
            Set<Voice> list=voiceEngine.getVoices();
            boolean es=false,tr=false;
            if (list!=null) for (Voice voice : list) {
                if (!voice.isNetworkConnectionRequired() && voice.getLocale()!=null) {
                    if ("es".equals(voice.getLocale().getLanguage())) es=true;
                    if ("tr".equals(voice.getLocale().getLanguage())) tr=true;
                }
            }
            message("Voces sin Internet: español "+(es?"disponible":"NO instalada")
                +" · turco "+(tr?"disponible":"NO instalada")
                +". Si falta una, el toque izquierdo no podrá reproducir ese idioma.");
        });
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
        if (voiceEngine != null) voiceEngine.shutdown();
        if (models != null) models.close();
        super.onDestroy();
    }
}
