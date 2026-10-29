package com.smartfk.keyboard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;


/**
 * This activity is only setup and diagnostics. The actual keyboard appears in
 * an Android text input. Its own UI remains exclusively translation text.
 */
public final class SetupActivity extends Activity {
    private TextView status;
    private TextView modelsStatus;
    private TextView voicesStatus;
    private TextView diagnostics;
    private OfflineTranslation models;
    private EditText tester;
    private boolean turkishReady;
    private boolean spanishReady;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(22),dp(18),dp(32));
        root.setBackgroundColor(0xFFFFFFFF);
        scroll.addView(root);
        TextView title = label("SmartFk · Configuración",23);
        root.addView(title);
        root.addView(label(
            "ESTA ES LA PANTALLA DE CONFIGURACIÓN, NO EL TECLADO.\n" +
            "1. Permite el micrófono.\n" +
            "2. Activa SmartFk como teclado Android.\n" +
            "3. Selecciona SmartFk.\n" +
            "4. Toca el campo de prueba de abajo para abrirlo.\n\n" +
            "La traducción neuronal Bergamot y el modelo de voz Whisper vienen dentro de la APK. No es necesario Internet.",
            15));
        status = label("",16);
        root.addView(status);

        Button permission = button(root,"1 · PERMITIR MICRÓFONO");
        permission.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},30);
            } else refreshState();
        });

        Button enable = button(root,"2 · HABILITAR SMARTFK EN ANDROID");
        enable.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        Button select = button(root,"3 · SELECCIONAR SMARTFK");
        select.setOnClickListener(v -> {
            tester.requestFocus();
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm!=null) imm.showInputMethodPicker();
        });

        root.addView(label("4 · PRUEBA AQUÍ EL TECLADO",17));
        tester = new EditText(this);
        tester.setTextSize(16);
        tester.setHint("Toca aquí para abrir SmartFk");
        tester.setMinLines(2);
        tester.setMaxLines(3);
        tester.setSingleLine(false);
        root.addView(tester);
        Button openTest=button(root,"ABRIR TECLADO EN ESTE CAMPO");
        openTest.setOnClickListener(v -> {
            tester.requestFocus();
            InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm!=null) imm.showSoftInput(tester,InputMethodManager.SHOW_IMPLICIT);
            refreshState();
        });

        root.addView(label("IDIOMA QUE VAS A HABLAR",17));
        RadioGroup choice = new RadioGroup(this);
        choice.setOrientation(RadioGroup.VERTICAL);
        String[] keys={"auto","tr","es"};
        String[] names={
            "Automático: español o turco (experimental)",
            "Turco → español",
            "Español → turco"
        };
        String savedMode=getSharedPreferences("smartfk",MODE_PRIVATE)
            .getString("source_language","tr");
        for(int i=0;i<keys.length;i++) {
            final String key=keys[i];
            RadioButton radio=new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setText(names[i]);
            radio.setTextColor(0xFF202020);
            choice.addView(radio);
            radio.setChecked(savedMode.equals(key));
            radio.setOnClickListener(v -> {
                getSharedPreferences("smartfk",MODE_PRIVATE).edit()
                    .putString("source_language",key).apply();
                refreshState();
            });
        }
        root.addView(choice);

        modelsStatus=label("Traducción Bergamot: no comprobada en esta apertura.",15);
        root.addView(modelsStatus);
        Button prepare=button(root,"COMPROBAR MODELOS NEURONALES OFFLINE");
        prepare.setOnClickListener(v -> {
            prepare.setEnabled(false);
            turkishReady=false;
            spanishReady=false;
            modelsStatus.setText("Comprobando modelos instalados…");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!turkishReady || !spanishReady) {
                    prepare.setEnabled(true);
                    modelsStatus.setText("La preparación sigue pendiente. " +
                        "" +
                        "" +
                        "Turco → español: " + (turkishReady?"LISTO":"PENDIENTE") +
                        "\nEspañol → turco: " + (spanishReady?"LISTO":"PENDIENTE"));
                }
            }, 25000);
            if(models==null) models=new OfflineTranslation(this);
            models.prepare(new OfflineTranslation.Callback() {
                @Override public void onSuccess(String s) { runOnUiThread(() -> {
                    if(s.startsWith("Modelo turco → español")) turkishReady=true;
                    if(s.contains("español → turco")) spanishReady=true;
                    modelsStatus.setText("Traducción turco → español: "+
                        (turkishReady?"LISTA":"comprobando")+
                        "\nTraducción español → turco: "+
                        (spanishReady?"LISTA":"comprobando"));
                    if(turkishReady && spanishReady) prepare.setEnabled(true);
                }); }
                @Override public void onFailure(String reason) { runOnUiThread(() -> {
                    prepare.setEnabled(true);
                    modelsStatus.setText("ERROR del modelo: "+reason);
                }); }
            });
        });

        Button translateTest=button(root,"PROBAR TRADUCTOR NEURONAL OFFLINE");
        translateTest.setOnClickListener(v -> {
            if (models==null) models=new OfflineTranslation(this);
            modelsStatus.setText("Probando traducción neuronal con texto turco conocido…");
            models.translate("tr","Merhaba, nasılsın?",new OfflineTranslation.Callback() {
                @Override public void onSuccess(String value) { runOnUiThread(() ->
                    modelsStatus.setText("Prueba texto turco: Merhaba, nasılsın?\n" +
                        "Resultado español: " + value)); }
                @Override public void onFailure(String err) { runOnUiThread(() ->
                    modelsStatus.setText("El traductor NO está funcionando: " + err)); }
            });
        });
        voicesStatus=label("Lectura en voz alta: opcional; no se necesita para escuchar y traducir.",15);
        root.addView(voicesStatus);
        diagnostics=label("",14);
        root.addView(diagnostics);
        Button check=button(root,"VER ESTADO Y ERRORES");
        check.setOnClickListener(v->refreshState());
        setContentView(scroll);
    }

    private TextView label(String value,int size){
        TextView text=new TextView(this);
        text.setTextColor(0xFF141414);
        text.setTextSize(size);
        text.setText(value);
        text.setPadding(0,dp(8),0,dp(8));
        return text;
    }
    private Button button(LinearLayout root,String title) {
        Button b=new Button(this);
        b.setText(title);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(8);
        root.addView(b,p);
        return b;
    }
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}

    @Override protected void onResume(){
        super.onResume();
        if(status!=null) refreshState();
    }
    private void refreshState() {
        boolean mic=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==
            PackageManager.PERMISSION_GRANTED;
        InputMethodManager imm=(InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled=false;
        if(imm!=null) for(android.view.inputmethod.InputMethodInfo item:
                imm.getEnabledInputMethodList()) {
            if(getPackageName().equals(item.getPackageName())) enabled=true;
        }
        String selected=Settings.Secure.getString(getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean current=selected!=null && selected.startsWith(getPackageName()+"/");
        status.setText("MICRÓFONO: "+(mic?"PERMITIDO":"FALTA PERMISO")+
            "\nSMARTFK HABILITADO: "+(enabled?"SÍ":"NO")+
            "\nSMARTFK SELECCIONADO: "+(current?"SÍ":"NO")+
            (current?"\nToca el campo de prueba y empieza a hablar.":"\nHabilita y selecciona SmartFk para comenzar."));
        String error=getSharedPreferences("smartfk",MODE_PRIVATE)
            .getString("last_asr_error","");
        String translationError=getSharedPreferences("smartfk",MODE_PRIVATE)
            .getString("last_translate_error","");
        android.content.SharedPreferences prefs=getSharedPreferences("smartfk",MODE_PRIVATE);
        String raw=prefs.getString("last_asr_text","");
        String source=prefs.getString("last_asr_language","");
        int pcm=prefs.getInt("last_pcm_average",-1);
        boolean silenced=prefs.getBoolean("audio_client_silenced",false);
        long heartbeat=prefs.getLong("last_audio_heartbeat",0);
        String audio = heartbeat==0?"Sin captura comprobada":
            (System.currentTimeMillis()-heartbeat<8000?"Captura reciente":"Captura antigua") +
            " · señal PCM: "+pcm+" · silenciado por Android: "+(silenced?"SÍ":"NO");
        diagnostics.setText("DIAGNÓSTICO REAL\nMicrófono: "+audio+
            "\nReconocimiento Vosk: "+(raw.isEmpty()?"Aún sin palabras reconocidas":source+": "+raw)+
            (error.isEmpty()?"":"\nError de audio: "+error)+
            (translationError.isEmpty()?"":"\nError de traducción: "+translationError));
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==30) refreshState();
    }
    @Override protected void onDestroy() {
        if(models!=null) models.close();
        super.onDestroy();
    }
}
