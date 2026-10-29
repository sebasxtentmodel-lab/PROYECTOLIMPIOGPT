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

/** AudioRecord runs independently of Whisper; decoding never blocks capture. */
final class WhisperFallback {
    private static final int WINDOW=16000*5;
    private static final String ASSET="whisper/ggml-base-q5_1.bin";
    private final Context context;
    private final ContinuousVoice.Events events;
    private final ExecutorService decoder=Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"smartfk-whisper");t.setPriority(Thread.NORM_PRIORITY-1);return t;
    });
    private final short[] window=new short[WINDOW];
    private final String configuredSource;
    private final LongSupplier latestVoskResult;
    private int used;
    private volatile boolean closed, decoding;
    private long modelHandle;

    WhisperFallback(Context context,ContinuousVoice.Events events,String configuredSource,
                    LongSupplier latestVoskResult) {
        this.context=context;
        this.events=events;
        this.configuredSource=configuredSource;
        this.latestVoskResult=latestVoskResult;
    }
    void feed(short[] audio,int count,long lastVoskAt) {
        if(closed)return;
        int n=Math.min(count,audio.length), index=0;
        while(index<n) {
            int take=Math.min(n-index,WINDOW-used);
            System.arraycopy(audio,index,window,used,take);
            used+=take;index+=take;
            if(used==WINDOW) {
                short[] candidate=Arrays.copyOf(window,WINDOW);
                used=0;
                long now=SystemClock.elapsedRealtime();
                if(!decoding && (lastVoskAt==0 || now-lastVoskAt>4200) && audible(candidate)) {
                    decoding=true;
                    decoder.execute(()->transcribe(candidate,lastVoskAt));
                }
            }
        }
    }
    private boolean audible(short[] audio) {
        long sum=0;
        for(int i=0;i<audio.length;i+=8)sum+=Math.abs((int)audio[i]);
        return sum/(audio.length/8)>65;
    }
    private void transcribe(short[] pcm,long voskSnapshot) {
        try {
            if(closed)return;
            if(modelHandle==0) {
                File out=new File(context.getNoBackupFilesDir(),"smartfk-whisper-base-q5_1.bin");
                if(!out.isFile()||out.length()<50000000L) {
                    File tmp=new File(out.getAbsolutePath()+".tmp");
                    try(InputStream in=context.getAssets().open(ASSET);
                        FileOutputStream fos=new FileOutputStream(tmp)) {
                        byte[] buffer=new byte[32768];int n;
                        while((n=in.read(buffer))!=-1)fos.write(buffer,0,n);
                    }
                    if(!tmp.renameTo(out))throw new IllegalStateException("Error guardando Whisper");
                }
                modelHandle=WhisperCpp.nativeOpen(out.getAbsolutePath());
                if(modelHandle==0)throw new IllegalStateException("No cargó modelo Whisper");
            }
            float[] data=new float[pcm.length];
            for(int i=0;i<pcm.length;i++)data[i]=pcm[i]/32768f;
            String[] lang="auto".equals(configuredSource)
                ?new String[]{"tr","es"}:new String[]{configuredSource};
            byte[] result=WhisperCpp.nativeTranscribe(modelHandle,data,lang,false,null,1,2);
            if(result==null || closed)return;
            String raw=new String(result,StandardCharsets.UTF_8);
            int sep=raw.indexOf('\n');
            String actual=sep<0?"":raw.substring(0,sep).trim();
            String words=sep<0?raw.trim():raw.substring(sep+1).trim();
            String src="es".equals(actual)?"es":"tr".equals(actual)?"tr":configuredSource;
            if(("tr".equals(src)||"es".equals(src))&&!words.isEmpty()
                    && !words.matches("(?i).*\\[(music|noise|silence)\\].*")) {
                // Vosk may recognize the same utterance while Whisper is decoding.
                // Do not append a second translation for the already-recognized speech.
                if (latestVoskResult.getAsLong() > voskSnapshot) return;
                events.finalWords(src,words);
            }
        } catch (Throwable ex) {
            android.util.Log.e("SmartFk","Whisper fallback failed",ex);
            events.error("Whisper: "+ex.getClass().getSimpleName()+": "+ex.getMessage());
        } finally {
            decoding=false;
        }
    }
    void reset(){used=0;}
    void close(){
        closed=true;
        decoder.execute(()->{
            if(modelHandle!=0){
                WhisperCpp.nativeClose(modelHandle);
                modelHandle=0;
            }
        });
        decoder.shutdown();
    }
}
