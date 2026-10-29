package com.smartfk.keyboard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.wasimaster.wmkeyboard.core.translate.BergamotNative;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real, fully local neural translator from WM Keyboard's Firefox Bergamot backend.
 * Model assets are bundled: never downloads or invokes ML Kit.
 * Only one route (two models) is resident to fit a 4 GB Android device.
 */
final class OfflineTranslation {
    interface Callback { void onSuccess(String value); void onFailure(String reason); }
    private final Context app;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"smartfk-bergamot");t.setPriority(Thread.NORM_PRIORITY-1);return t;
    });
    private final Handler main=new Handler(Looper.getMainLooper());
    private long service, first, second;
    private String loadedRoute="";
    private volatile boolean closed;
    private volatile boolean libraryOk;
    // At most one unfinished preview is queued. Every newer hypothesis replaces it.
    private Preview latestPreview;
    private boolean previewScheduled;
    private static final class Preview {
        final String source,phrase;
        final Callback callback;
        Preview(String source,String phrase,Callback callback) {
            this.source=source;this.phrase=phrase;this.callback=callback;
        }
    }


    OfflineTranslation(Context context) {
        app=context.getApplicationContext();
        try {
            Class.forName("com.wasimaster.wmkeyboard.core.translate.BergamotNative");
            libraryOk=true;
        } catch (Throwable e) {
            android.util.Log.e("SmartFk","Bergamot library could not load",e);
        }
    }
    void prepare(Callback cb) {
        prepareSource("tr",cb);
        prepareSource("es",cb);
    }
    void prepareSource(String src,Callback cb) {
        if (!libraryOk) { error(cb,"Motor Bergamot no cargó"); return; }
        worker.execute(()->{
            try {
                if(closed) return;
                setup(src);
                ok(cb,src.equals("tr")?"Modelo neuronal turco → español listo":
                    "Modelo neuronal español → turco listo");
            }catch(Throwable ex){error(cb,describe(ex));}
        });
    }
    void translate(String source,String phrase,Callback cb) {
        if (phrase==null || phrase.trim().isEmpty()) return;
        if (!libraryOk) { error(cb,"Falta biblioteca nativa Bergamot");return; }
        worker.execute(()->{
            if(closed)return;
            try { ok(cb,translateNow(source,phrase)); }
            catch(Throwable e){error(cb,describe(e));}
        });
    }

    /**
     * Live ASR sends new hypotheses several times per second. Never queue one
     * expensive neural inference for every hypothesis: it can delay definitive
     * sentences indefinitely. Only the newest waiting preview is retained.
     * Final translations use translate() and are queued ahead of later previews.
     */
    synchronized void translatePreview(String source,String phrase,Callback cb) {
        if(closed || phrase==null || phrase.trim().isEmpty())return;
        if(!libraryOk) {error(cb,"Falta biblioteca nativa Bergamot");return;}
        latestPreview=new Preview(source,phrase,cb);
        if(previewScheduled)return;
        previewScheduled=true;
        worker.execute(this::drainOnePreview);
    }

    private void drainOnePreview() {
        Preview item;
        synchronized(this){ item=latestPreview;latestPreview=null; }
        if(item!=null && !closed) {
            try {ok(item.callback,translateNow(item.source,item.phrase));}
            catch(Throwable e){error(item.callback,describe(e));}
        }
        synchronized(this) {
            if(latestPreview!=null && !closed) worker.execute(this::drainOnePreview);
            else previewScheduled=false;
        }
    }

    /**
     * Preserve the real Turkish/Spanish casing required by the neural model.
     * Vosk hypotheses arrive lowercase and without terminal punctuation.
     */
    static String prepareText(String source,String phrase){
        String text=phrase.trim().replaceAll("\\s+"," ");
        if(text.isEmpty())return text;
        java.util.Locale locale="tr".equals(source)?new java.util.Locale("tr","TR"):
            new java.util.Locale("es","ES");
        int i=text.offsetByCodePoints(0,1);
        text=text.substring(0,i).toUpperCase(locale)+text.substring(i);
        char last=text.charAt(text.length()-1);
        if(last!='.'&&last!='?'&&last!='!'&&last!='…')text+=".";
        return text;
    }
    private String translateNow(String source,String phrase) throws Exception {
        setup("es".equals(source)?"es":"tr");
        byte[][] inputs={prepareText(source,phrase).getBytes(StandardCharsets.UTF_8)};
        byte[][] result=BergamotNative.nativePivot(service,first,second,inputs);
        if(result==null || result.length==0 || result[0]==null)
            throw new IllegalStateException("Bergamot devolvió resultado vacío");
        String translated=new String(result[0],StandardCharsets.UTF_8).trim();
        if(translated.isEmpty())throw new IllegalStateException("Modelo sin salida");
        return translated;
    }
    private void setup(String source) throws Exception {
        if(source.equals(loadedRoute)&&first!=0&&second!=0)return;
        releaseRoute();
        if(service==0)service=BergamotNative.nativeNewService(128);
        if(service==0)throw new IllegalStateException("No pudo abrir servicio Bergamot");
        String firstKey=source.equals("tr")?"tr-en":"es-en";
        String secondKey=source.equals("tr")?"en-es":"en-tr";
        first=load(firstKey);
        second=load(secondKey);
        if(first==0||second==0)throw new IllegalStateException("Modelo Bergamot vacío");
        loadedRoute=source;
    }
    private long load(String lang) throws Exception {
        File dir=new File(app.getNoBackupFilesDir(),"smartfk-models/"+lang);
        File model=copy(lang,dir,"model.bin");
        File lex=copy(lang,dir,"lex.bin");
        File vocab=copy(lang,dir,"vocab.spm");
        return BergamotNative.nativeLoadModel(model.getAbsolutePath(),
            lex.getAbsolutePath(),new String[]{vocab.getAbsolutePath()},false);
    }
    private File copy(String lang,File dir,String name) throws Exception {
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Sin espacio: "+lang);
        String asset="bergamot/"+lang+"/"+name;
        File dest=new File(dir,name);
        // All assets originate from SHA-256 verified upstream build packages.
        long size=app.getAssets().openFd(asset).getLength();
        if(dest.isFile()&&dest.length()==size)return dest;
        File temp=new File(dir,name+".partial");
        try(InputStream in=app.getAssets().open(asset);
            FileOutputStream out=new FileOutputStream(temp)){
            byte[] buffer=new byte[32768];
            int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
        if(temp.length()!=size||!temp.renameTo(dest))
            throw new IllegalStateException("Error al copiar modelo "+asset);
        return dest;
    }
    private void releaseRoute() {
        if(first!=0){BergamotNative.nativeFreeModel(first);first=0;}
        if(second!=0){BergamotNative.nativeFreeModel(second);second=0;}
        loadedRoute="";
    }
    private void ok(Callback cb,String msg){main.post(()->{if(!closed)cb.onSuccess(msg);});}
    private void error(Callback cb,String msg){main.post(()->{if(!closed)cb.onFailure(msg);});}
    private String describe(Throwable e){return e.getClass().getSimpleName()+": "+e.getMessage();}
    void close(){
        closed=true;
        worker.execute(()->{
            try{releaseRoute();
                if(service!=0){BergamotNative.nativeFreeService(service);service=0;}
            }catch(Throwable ignored){}
        });
        worker.shutdown();
    }
}
