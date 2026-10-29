package com.smartfk.keyboard;

import android.content.Context;
import android.os.SystemClock;
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCpp;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/** Background Turkish-specialized Sherpa-ONNX ASR and multilingual whisper.cpp.
 * AudioRecord continuously supplies chunks without ever waiting for inference.
 */
final class WhisperFallback {
    private static final String WHISPER_ASSET="whisper/ggml-base-q5_1.bin";
    private final Context context;
    private final ContinuousVoice.Events events;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"smartfk-turkish-sherpa");t.setPriority(Thread.NORM_PRIORITY-1);return t;
    });
    private final short[] window;
    private final String configuredSource;
    private final LongSupplier latestVoskResult;
    private final boolean specializedTurkish;
    private int used;
    private volatile boolean closed, decoding, specializedWorking=true;
    private long modelHandle;
    private TurkishSherpa turkishEngine;

    WhisperFallback(Context context, ContinuousVoice.Events events, String configuredSource,
                    LongSupplier latestVoskResult) {
        this.context=context.getApplicationContext();
        this.events=events;
        this.configuredSource=configuredSource;
        this.latestVoskResult=latestVoskResult;
        specializedTurkish=!"es".equals(configuredSource);
        window=new short[16000*(specializedTurkish?8:5)];
    }
    boolean prefersTurkishFinals(){ return specializedTurkish && specializedWorking; }
    void feed(short[] audio,int count,long lastVoskAt) {
        if(closed)return;
        int n=Math.min(count,audio.length),index=0;
        while(index<n){
            int take=Math.min(n-index,window.length-used);
            System.arraycopy(audio,index,window,used,take);
            used+=take;index+=take;
            if(used==window.length){
                short[] candidate=Arrays.copyOf(window,window.length);
                used=0;
                if(!decoding && audible(candidate) &&
                        (specializedTurkish || lastVoskAt==0 ||
                         SystemClock.elapsedRealtime()-lastVoskAt>4200)){
                    decoding=true;
                    decoder.execute(()->transcribe(candidate,lastVoskAt));
                }
            }
        }
    }
    private boolean audible(short[] audio){
        long sum=0;
        for(int i=0;i<audio.length;i+=8)sum+=Math.abs((int)audio[i]);
        return sum/(audio.length/8)>65;
    }
    private void transcribe(short[] pcm,long voskSnapshot){
        try{
            if(closed)return;
            String source,words;
            if(specializedTurkish && specializedWorking){
                if(turkishEngine==null)turkishEngine=new TurkishSherpa(context);
                words=turkishEngine.transcribe(pcm);
                source="tr";
            } else {
                String result=runWhisperCpp(pcm);
                int sep=result.indexOf('\n');
                String guessed=sep<0?"":result.substring(0,sep).trim();
                words=sep<0?result.trim():result.substring(sep+1).trim();
                source="es".equals(guessed)?"es":"tr".equals(guessed)?"tr":configuredSource;
                if(latestVoskResult.getAsLong()>voskSnapshot)return;
            }
            if(closed || words.isEmpty() || !("tr".equals(source)||"es".equals(source)))return;
            if(words.matches("(?i).*(\\[(music|noise|silence)\\]|al\\u0131nt\\u0131|m\\u00fczik).*"))return;
            events.finalWords(source,words);
        } catch(Throwable error) {
            if(specializedTurkish)specializedWorking=false;
            android.util.Log.e("SmartFk","Offline ASR failed",error);
            events.error("Motor turco: "+error.getClass().getSimpleName()+": "+error.getMessage());
        } finally {
            decoding=false;
        }
    }
    private String runWhisperCpp(short[] pcm)throws Exception {
        if(modelHandle==0){
            File out=new File(context.getNoBackupFilesDir(),"smartfk-whisper-base-q5_1.bin");
            if(!out.isFile()||out.length()<50000000L){
                File tmp=new File(out.getAbsolutePath()+".tmp");
                try(InputStream in=context.getAssets().open(WHISPER_ASSET);
                    FileOutputStream os=new FileOutputStream(tmp)){
                    byte[] buf=new byte[32768];int n;
                    while((n=in.read(buf))!=-1)os.write(buf,0,n);
                }
                if(!tmp.renameTo(out))throw new IllegalStateException("Error guardando Whisper");
            }
            modelHandle=WhisperCpp.nativeOpen(out.getAbsolutePath());
            if(modelHandle==0)throw new IllegalStateException("No cargó modelo Whisper");
        }
        float[] input=new float[pcm.length];
        for(int i=0;i<pcm.length;i++)input[i]=pcm[i]/32768f;
        String[] languages="auto".equals(configuredSource)?
                new String[]{"tr","es"}:new String[]{configuredSource};
        byte[] result=WhisperCpp.nativeTranscribe(modelHandle,input,languages,false,null,1,2);
        return result==null?"":new String(result,StandardCharsets.UTF_8);
    }
    void reset(){used=0;}
    void close(){
        closed=true;
        decoder.execute(()->{
            if(turkishEngine!=null){turkishEngine.close();turkishEngine=null;}
            if(modelHandle!=0){WhisperCpp.nativeClose(modelHandle);modelHandle=0;}
        });
        decoder.shutdown();
    }
}
