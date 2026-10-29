package com.smartfk.keyboard;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Debug-only: fails if the actual embedded Bergamot neural models cannot translate. */
public final class NeuralSmokeTestActivity extends Activity {
    private static final String TAG="SMARTFK_NEURAL_TEST";
    private OfflineTranslation engine;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView info=new TextView(this);
        info.setText("Prueba REAL: Bergamot offline turco↔español");
        info.setTextSize(18);
        setContentView(info);
        engine=new OfflineTranslation(this);
        engine.translate("tr","Merhaba, nasılsın?",new OfflineTranslation.Callback() {
            @Override public void onSuccess(String value) {
                Log.i(TAG,"TR_ES_OK: "+value);
                engine.translate("es","Hola, ¿cómo estás?",new OfflineTranslation.Callback() {
                    @Override public void onSuccess(String second) {
                        Log.i(TAG,"ES_TR_OK: "+second);
                        info.setText("Traducción real TR→ES: "+value+
                            "\nTraducción real ES→TR: "+second);
                    }
                    @Override public void onFailure(String reason) {
                        Log.e(TAG,"ES_TR_FAILED: "+reason);
                        info.setText(reason);
                    }
                });
            }
            @Override public void onFailure(String reason) {
                Log.e(TAG,"TR_ES_FAILED: "+reason);
                info.setText(reason);
            }
        });
    }
    @Override public void onDestroy(){
        if(engine!=null)engine.close();
        super.onDestroy();
    }
}
