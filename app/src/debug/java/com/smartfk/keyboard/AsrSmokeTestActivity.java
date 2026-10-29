package com.smartfk.keyboard;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCpp;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Debug-only OFFLINE sample-to-text test, never shipped in a release build.
 * Uses real 16kHz voice fixtures rather than checking that files merely exist.
 */
public final class AsrSmokeTestActivity extends Activity {
    private static final String TAG="SMARTFK_ASR_TEST";
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        new Thread(()->{
            try {
                // Real voice PCM → Vosk → Bergamot → translated text.
                String turkish = testVosk("tr","tr_fixture.pcm");
                String spanish = testVosk("es","es_fixture.pcm");
                if(turkish.trim().isEmpty() || spanish.trim().isEmpty())
                    throw new IllegalStateException("Vosk returned empty voice transcription");
                OfflineTranslation nmt=new OfflineTranslation(this);
                try {
                    testTranslation("tr",turkish,nmt);
                    testTranslation("es",spanish,nmt);
                } finally {
                    nmt.close();
                }
                testWhisper("tr","tr_fixture.pcm");
                testWhisper("es","es_fixture.pcm");
                Log.i(TAG,"ASR_TEST_COMPLETE");
            } catch(Throwable ex) {
                Log.e(TAG,"ASR_TEST_FAILED",ex);
            }
        },"smartfk-asr-test").start();
    }
    private String testVosk(String lang,String fixture) throws Exception {
        File model=new File(getCacheDir(),"models-"+lang);
        if(!model.exists()) model.mkdirs();
        copyDirectory("models/"+lang,model);
        StringBuilder recognized=new StringBuilder();
        try(Model m=new Model(model.getAbsolutePath());
            Recognizer recognizer=new Recognizer(m,16000.0f);
            InputStream in=getAssets().open(fixture)){
            byte[] bytes=new byte[3200];
            int n;
            while((n=in.read(bytes))!=-1) {
                if(recognizer.acceptWaveForm(bytes,n)) {
                    String text=new JSONObject(recognizer.getResult()).optString("text","");
                    if(!text.isEmpty()) recognized.append(text).append(" ");
                }
            }
            String tail=new JSONObject(recognizer.getFinalResult()).optString("text","");
            if(!tail.isEmpty()) recognized.append(tail);
        }
        Log.i(TAG,lang.toUpperCase()+"_VOSK_RESULT="+recognized.toString().trim());
        return recognized.toString().trim();
    }
    private void testTranslation(String lang,String recognized,OfflineTranslation engine)
            throws Exception {
        java.util.concurrent.CountDownLatch latch=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> translation=
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> failure=
            new java.util.concurrent.atomic.AtomicReference<>();
        engine.translate(lang,recognized,new OfflineTranslation.Callback() {
            @Override public void onSuccess(String value) {
                translation.set(value);
                latch.countDown();
            }
            @Override public void onFailure(String reason) {
                failure.set(reason);
                latch.countDown();
            }
        });
        if(!latch.await(100,java.util.concurrent.TimeUnit.SECONDS))
            throw new IllegalStateException("Timeout voice → neural translation ("+lang+")");
        if(failure.get()!=null || translation.get()==null || translation.get().trim().isEmpty())
            throw new IllegalStateException("Voice → translation failed "+lang+": "+failure.get());
        Log.i(TAG,lang.toUpperCase()+"_AUDIO_TRANSLATED="+translation.get());
    }
    private void testWhisper(String lang,String fixture) throws Exception {
        File path=new File(getCacheDir(),"ggml-base-q5_1.bin");
        if(!path.isFile() || path.length()<50_000_000L)
            copyFile("whisper/ggml-base-q5_1.bin",path);
        byte[] bytes;
        try(InputStream in=getAssets().open(fixture)){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))!=-1) out.write(buf,0,n);
            bytes=out.toByteArray();
        }
        float[] samples=new float[bytes.length/2];
        for(int i=0;i<samples.length;i++) {
            int little=bytes[2*i]&255, high=bytes[2*i+1];
            samples[i]=(short)(little | (high<<8))/32768.0f;
        }
        long model=WhisperCpp.nativeOpen(path.getAbsolutePath());
        if(model==0)throw new IllegalStateException("Whisper nativeOpen failed");
        try {
            byte[] response=WhisperCpp.nativeTranscribe(model,samples,new String[]{lang},false,null,1,2);
            String text=response==null?"":new String(response,StandardCharsets.UTF_8).trim();
            Log.i(TAG,lang.toUpperCase()+"_WHISPER_RESULT="+text);
        } finally {WhisperCpp.nativeClose(model);}
    }
    private void copyDirectory(String path,File target) throws Exception {
        String[] members=getAssets().list(path);
        if(members==null)throw new IllegalStateException(path);
        if(members.length==0) {
            copyFile(path,target);
        } else {
            if(!target.exists()&&!target.mkdirs())throw new IllegalStateException(target.toString());
            for(String name:members)copyDirectory(path+"/"+name,new File(target,name));
        }
    }
    private void copyFile(String asset,File dest) throws Exception {
        try(InputStream in=getAssets().open(asset);
            FileOutputStream out=new FileOutputStream(dest)){
            byte[] buffer=new byte[32768];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
    }
}
