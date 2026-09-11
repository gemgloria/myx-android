/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public final class FloatingService extends Service {
    public static final String STOP="com.yexin.xiangqi.STOP_FLOATING";
    private static volatile FloatingService instance;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService engineWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"floating-pikafish"));
    private final ExecutorService visionWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"board-recognition"));
    private final PikafishClient engine=new PikafishClient();
    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader images;
    private BoardRecognizer recognizer;
    private WindowManager windows;
    private WindowManager.LayoutParams panelParams;
    private LinearLayout panel,controls;
    private TextView status;
    private final TextView[] probabilityViews=new TextView[3];
    private String probabilityFen;
    private View framePulse;
    private Button startButton,timeButton,foldButton;
    private int captureWidth,captureHeight,density;
    private int[] capturePixels,captureColumns;
    private volatile boolean destroyed,recognizing,gestureInFlight;
    private volatile long lastSubmitted,lastCapturedAt;
    private volatile int captureInterval=OverlayTiming.PAUSED_CAPTURE_MS;
    private boolean engineReady,engineBusy,stateLoading,paused=true,auto,oneShot,collapsed,captureGeometryValid=true,blink,fastTaps;
    private long revision,latestFrameAt,candidateReadyAt,pendingSince,boundWindow,acceptFramesAfter;
    private int candidateFrames,randomMode=OverlayTiming.DEFAULT_RANDOM_MODE;
    private int searchThreads=4,hashMb=256;
    private boolean prethink=true;
    private final RecognitionRecovery recovery=new RecognitionRecovery();
    private final MoveRetryPolicy moveRetry=new MoveRetryPolicy();
    private BoardRecognizer.Observation latest,candidateBasis;
    private ScreenGame game;
    private String targetPackage,candidateMove,message="切换到象棋游戏后点开始";
    private EngineState candidateAfter;
    private PikafishClient.Ticket ticket;
    private Boolean manualTurn;

    public static boolean running(){return instance!=null;}
    public static void externalPause(String reason) {
        FloatingService s=instance;if(s!=null)s.ui.post(()->s.pause(reason));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate() {
        super.onCreate();instance=this;windows=(WindowManager)getSystemService(WINDOW_SERVICE);
        randomMode=getSharedPreferences("overlay",0).getInt("random_mode",OverlayTiming.DEFAULT_RANDOM_MODE);
        if(randomMode!=1&&randomMode!=2)randomMode=OverlayTiming.DEFAULT_RANDOM_MODE;
        fastTaps=getSharedPreferences("overlay",0).getBoolean("fast_taps",true);
        searchThreads=getSharedPreferences("overlay",0).getInt("search_threads",4);
        if(searchThreads!=1&&searchThreads!=2&&searchThreads!=4)searchThreads=4;
        searchThreads=Math.min(searchThreads,Math.max(1,Runtime.getRuntime().availableProcessors()));
        hashMb=getSharedPreferences("overlay",0).getInt("hash_mb",256);
        if(hashMb!=128&&hashMb!=256&&hashMb!=512)hashMb=256;
        if(((ActivityManager)getSystemService(ACTIVITY_SERVICE)).isLowRamDevice())hashMb=128;
        prethink=getSharedPreferences("overlay",0).getBoolean("prethink",true);
        captureThread=new HandlerThread("screen-capture");captureThread.start();captureHandler=new Handler(captureThread.getLooper());
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null||STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(projection!=null)return START_NOT_STICKY;
        try {
            startNotification();
            Intent token=intent.getParcelableExtra("projection");
            if(token==null)throw new IOException("请重新授权屏幕捕获");
            recognizer=new BoardRecognizer(getAssets().open("vision/wooden-v1.bin"));
            DisplayMetrics metrics=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);
            captureWidth=metrics.widthPixels;captureHeight=metrics.heightPixels;density=metrics.densityDpi;
            if(Build.VERSION.SDK_INT>=30){Rect b=windows.getMaximumWindowMetrics().getBounds();captureWidth=b.width();captureHeight=b.height();}
            projection=((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE))
                .getMediaProjection(intent.getIntExtra("resultCode",Activity.RESULT_CANCELED),token);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop(){ui.post(()->stopSelf());}
                @Override public void onCapturedContentResize(int width,int height) {
                    if(width!=captureWidth||height!=captureHeight)
                        ui.post(()->{captureGeometryValid=false;pause("画面尺寸改变，请保持竖屏并重新启动屏幕识别");});
                }
            },captureHandler);
            images=ImageReader.newInstance(captureWidth,captureHeight,PixelFormat.RGBA_8888,3);
            images.setOnImageAvailableListener(this::capture,captureHandler);
            display=projection.createVirtualDisplay("Xiangqi board",captureWidth,captureHeight,density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,images.getSurface(),null,captureHandler);
            showPanel();
            engineWorker.submit(()->{
                try {
                    engine.start(EngineFiles.executable(this),EngineFiles.model(this),searchThreads);
                    engine.configureHash(hashMb);
                    ui.post(()->{if(destroyed)return;engineReady=true;if(game!=null)refreshState(true);else if(paused)show("切换到象棋游戏后点开始");});
                } catch(Exception e){ui.post(()->pause("引擎启动失败："+shortError(e)));}
            });
            ui.postDelayed(watchdog,800);
        } catch(Exception e) {
            Toast.makeText(this,"启动失败："+shortError(e),Toast.LENGTH_LONG).show();stopSelf();
        }
        return START_NOT_STICKY;
    }
    private void startNotification() {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("xiangqi-capture","象棋屏幕识别",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,41,new Intent(this,FloatingService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open=PendingIntent.getActivity(this,42,new Intent(this,AssistantActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"xiangqi-capture").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("象棋悬浮助手正在读取屏幕").setContentText("点击悬浮窗开始或暂停；可随时停止屏幕识别")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"停止助手",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(19,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(19,n);
    }
    private void capture(ImageReader source) {
        Image image=null;
        try {
            image=source.acquireLatestImage();if(image==null||destroyed)return;
            long now=SystemClock.elapsedRealtime();lastCapturedAt=now;
            if(recognizing||gestureInFlight||now-lastSubmitted<captureInterval)return;
            lastSubmitted=now;recognizing=true;
            final long frameWindow=XiangqiGestureService.windowGeneration();
            int w=Math.min(900,captureWidth),h=Math.round(captureHeight*(w/(float)captureWidth));
            // There is only one in-flight recognition. Reuse its buffer and precompute
            // horizontal offsets instead of allocating and dividing for every screen pixel.
            if(capturePixels==null||capturePixels.length!=w*h){capturePixels=new int[w*h];captureColumns=new int[w];}
            final int[] pixels=capturePixels;Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();
            int stride=plane.getRowStride(),pixel=plane.getPixelStride();
            for(int x=0;x<w;x++)captureColumns[x]=Math.min(captureWidth-1,(int)((x+.5f)*captureWidth/w))*pixel;
            for(int y=0;y<h;y++) {
                int row=Math.min(captureHeight-1,(int)((y+.5f)*captureHeight/h))*stride;
                for(int x=0;x<w;x++) {
                    int offset=row+captureColumns[x];
                    int r=buffer.get(offset)&255,g=buffer.get(offset+1)&255,b=buffer.get(offset+2)&255;
                    pixels[y*w+x]=0xFF000000|(r<<16)|(g<<8)|b;
                }
            }
            final float scaleX=captureWidth/(float)w,scaleY=captureHeight/(float)h;
            BoardRecognizer.Frame frame=new BoardRecognizer.Frame(w,h,pixels);
            visionWorker.submit(()->{
                try {
                    BoardRecognizer.Observation raw=recognizer.recognize(frame);BoardRecognizer.Geometry g=raw.geometry;
                    BoardRecognizer.Geometry screen=new BoardRecognizer.Geometry(g.left*scaleX,g.top*scaleY,g.right*scaleX,g.bottom*scaleY);
                    BoardRecognizer.Observation result=new BoardRecognizer.Observation(screen,raw.squares,raw.redBottom,raw.confidence,raw.selectedSquare,raw.moveHints);
                    ui.post(()->{if(frameWindow==XiangqiGestureService.windowGeneration())observed(result,now);});
                } catch(Exception e){ui.post(()->{if(frameWindow==XiangqiGestureService.windowGeneration())unrecognized(shortError(e),now);});}
                finally{recognizing=false;}
            });
        } catch(Exception e){recognizing=false;ui.post(()->pause("无法读取屏幕，请重新启动屏幕识别"));}
        finally{if(image!=null)image.close();}
    }
    private boolean captureSizeMatches() {
        DisplayMetrics metrics=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);
        return captureGeometryValid&&metrics.widthPixels==captureWidth&&metrics.heightPixels==captureHeight;
    }
    private boolean targetAppVisible() {
        return captureSizeMatches()&&targetPackage!=null&&targetPackage.equals(XiangqiGestureService.activePackage());
    }
    private boolean targetVisible() {
        return targetAppVisible()&&boundWindow==XiangqiGestureService.windowGeneration();
    }
    private boolean checkTargetWindow() {
        if(!captureSizeMatches()){pause("画面尺寸改变，请重新启动屏幕识别");return false;}
        String active=XiangqiGestureService.activePackage();
        if(active==null){retryRecognition("等待游戏窗口恢复，暂不点击",SystemClock.elapsedRealtime(),false);return false;}
        if(!active.equals(targetPackage)){pause("已离开原游戏窗口\n返回后点开始继续");return false;}
        long window=XiangqiGestureService.windowGeneration();
        if(boundWindow!=window) {
            // Same-app result pages/dialogs may change the window. Rebind for observation
            // only; two newly captured valid frames are still required before any action.
            boundWindow=window;acceptFramesAfter=SystemClock.elapsedRealtime();
            revision++;cancelSearch();cancelGestures();
            retryRecognition("游戏窗口变化，等待棋盘",acceptFramesAfter,false);return false;
        }
        return true;
    }
    private void observed(BoardRecognizer.Observation observation,long at) {
        if(destroyed||gestureInFlight||at<=acceptFramesAfter)return;
        latest=observation;latestFrameAt=at;
        if(paused||targetPackage==null)return;
        if(!checkTargetWindow()||!recovery.observe(observation,at))return;
        if(recovery.startsNewGame(observation,game,at)) {
            cancelSearch();revision++;cancelGestures();
            game=null;manualTurn=null;pendingSince=0;moveRetry.clear();clearProbability();recovery.newGameStarted();
        } else if(recovery.waitingOpening()) {show("对局结束 · 等待下一局棋盘\n将自动识别，无需再点开始");return;}
        if(game==null) {
            if(!observation.opening()&&manualTurn==null) {
                pause(side(observation.redBottom)+" · 中局已识别\n点选项确认当前轮到谁");return;
            }
            game=new ScreenGame();game.reset(observation,manualTurn);manualTurn=null;revision++;
            recovery.accepted();updateCaptureRate();
            movePanelOutsideBoard(observation.geometry);refreshState(true);return;
        }
        if(game.state==null){if(!stateLoading)refreshState(false);return;}
        ScreenGame.Change change=game.observe(observation);
        if(change==ScreenGame.Change.WAIT_ENGINE)return;
        if(change==ScreenGame.Change.UNKNOWN) {
            retryRecognition("局面核对未通过，暂不点击",at,false);return;
        }
        boolean recovered=recovery.retrying();recovery.accepted();updateCaptureRate();
        if(change!=ScreenGame.Change.SAME) {
            cancelSearch();revision++;pendingSince=0;candidateMove=null;candidateBasis=null;
            clearProbability();
            if(!game.hasPending())moveRetry.clear();
            if(game.state==null){refreshState(false);return;}
            showTurn();
        }
        if(game.state.finished()){moveRetry.clear();showFinishedProbability();waitForNextOpening();return;}
        if(game.hasPending()) {
            String pending=game.pendingMove();
            if(pending==null){moveRetry.clear();return;}
            try{moveRetry.lock(pending,at);}catch(IllegalStateException e){pause(e.getMessage());return;}
            if(moveRetry.ready(SystemClock.elapsedRealtime()))dispatchPendingMove(true);
            else show("等待确认当前着法 · 第 "+moveRetry.attempts()+" 次\n未完成将自动重试");
            return;
        }
        if(moveRetry.locked())moveRetry.clear();
        if(candidateMove!=null) {
            if(!observation.same(candidateBasis)||!Arrays.equals(observation.squares,game.state.position.squares)) {
                retryRecognition("棋盘位置变化，重新核对",at,false);return;
            }
            if(at>candidateReadyAt)candidateFrames++;
            if(candidateFrames>=2)sendMove();
            return;
        }
        if(recovered)showTurn();
        if(game.ourTurn())maybeSearch();else maybeEvaluateProbability();
    }
    private void unrecognized(String reason,long at) {
        if(destroyed||gestureInFlight||at<=acceptFramesAfter)return;
        latest=null;latestFrameAt=at;
        if(paused||targetPackage==null)return;
        if(!checkTargetWindow())return;
        retryRecognition(reason,at,true);
    }
    private void retryRecognition(String reason,long at,boolean missingBoard) {
        latest=null;latestFrameAt=at;candidateFrames=0;
        if(paused||targetPackage==null)return;
        if(recovery.failed(at,missingBoard)) {
            revision++;cancelSearch();cancelGestures();
        }
        updateCaptureRate();
        show((recovery.waitingOpening()?"等待下一局":"识别重试")+" · 第 "+recovery.failures()+" 次\n"+reason);
    }
    private void waitForNextOpening() {
        if(!auto){pause("对局结束 · 点开始等待下一局");return;}
        revision++;cancelSearch();cancelGestures();pendingSince=0;moveRetry.clear();
        recovery.waitForOpening();updateCaptureRate();
        show("对局结束 · 等待下一局棋盘\n将自动识别，无需再点开始");
    }
    private void updateCaptureRate() {
        captureInterval=OverlayTiming.captureInterval(paused,recovery.retrying());
    }
    private void refreshState(boolean reset) {
        if(game==null||destroyed)return;
        if(!engineReady){show("准备离线引擎…");return;}
        final long id=revision;final String base=game.initialFen;final List<String> moves=game.moves();
        stateLoading=true;show("核对棋盘与轮次…");
        engineWorker.submit(()->{
            try {
                if(reset)engine.newGame();EngineState result=engine.state(base,moves);
                ui.post(()->{
                    if(destroyed||id!=revision||game==null)return;
                    stateLoading=false;game.state=result;
                    if(result.finished()){showFinishedProbability();waitForNextOpening();return;}
                    showTurn();if(game.ourTurn())maybeSearch();else maybeEvaluateProbability();
                });
            } catch(Exception e){ui.post(()->{if(id==revision)pause("局面核对失败："+shortError(e));});}
        });
    }
    private void maybeSearch() {
        if(destroyed||paused||!engineReady||engineBusy||stateLoading||game==null||!game.ourTurn()||game.hasPending()||candidateMove!=null||(!auto&&!oneShot))return;
        if(latest==null||!recovery.stable()||recovery.waitingOpening()||!Arrays.equals(latest.squares,game.state.position.squares)||!targetVisible())return;
        final long id=revision;final List<String> moves=game.moves();final String base=game.initialFen;
        final EngineState before=game.state;final BoardRecognizer.Observation basis=latest;
        final boolean userToMove=before.position.redTurn==game.ownRed;
        final int budget=OverlayTiming.randomThinkMillis(randomMode);
        final int threads=searchThreads,hash=hashMb;
        final PikafishClient.Ticket job=new PikafishClient.Ticket();ticket=job;engineBusy=true;
        show(side(game.ownRed)+" · 思考 "+OverlayTiming.seconds(budget)+" 秒");
        engineWorker.submit(()->{
            try {
                if(job.cancelled)return;
                engine.configureThreads(threads);engine.configureHash(hash);
                String move=engine.search(base,moves,budget,job,line->{
                    String[] parts=line.split(" ");String depth=parts.length>2?parts[2]:"";
                    WdlProbabilities wdl=WdlProbabilities.parse(line,userToMove);
                    ui.post(()->{if(!destroyed&&id==revision&&ticket==job&&engineBusy&&!paused){
                        if(wdl!=null)setProbability(wdl);
                        show(side(game.ownRed)+" · 思考 "+OverlayTiming.seconds(budget)+" 秒\n搜索深度 "+depth);
                    }});
                });
                if(job.cancelled)return;
                if(!before.legalMoves.contains(move))throw new IOException("引擎未返回合法走法");
                List<String> next=new ArrayList<>(moves);next.add(move);EngineState after=engine.state(base,next);
                ui.post(()->{
                    if(destroyed||id!=revision||paused||job.cancelled)return;
                    engineBusy=false;ticket=null;candidateMove=move;candidateAfter=after;candidateBasis=basis;
                    candidateReadyAt=SystemClock.elapsedRealtime();candidateFrames=0;
                    show("准备 "+before.position.notation(move)+"\n正在再次核对屏幕");
                    requestFreshFrame();
                });
            } catch(CancellationException ignored) { }
            catch(Exception e){ui.post(()->{if(id==revision)pause("计算失败："+shortError(e));});}
        });
    }
    /** Analyze the confirmed opponent position to warm the transposition table.
     * This is NOT a predicted-move ponderhit: discard bestmove, stop on board changes,
     * retain only the engine's hash, and search again with the actual move history. */
    private void maybeEvaluateProbability() {
        if(destroyed||paused||!engineReady||engineBusy||stateLoading||game==null||game.state==null||game.state.finished()||game.ourTurn()||game.hasPending())return;
        if(latest==null||!recovery.stable()||recovery.waitingOpening()||!targetVisible())return;
        final String fen=game.state.position.fen;
        if(fen.equals(probabilityFen))return;
        final long id=revision;final List<String> moves=game.moves();final String base=game.initialFen;
        final boolean userToMove=game.state.position.redTurn==game.ownRed;
        final int budget=prethink?0:OverlayTiming.PROBABILITY_EVAL_MS;
        final int threads=searchThreads,hash=hashMb;
        final PikafishClient.Ticket job=new PikafishClient.Ticket();ticket=job;engineBusy=true;
        if(prethink)show(side(game.ownRed)+" · 等待对方\n预先思考中 · "+threads+" 线程");
        engineWorker.submit(()->{
            try {
                if(job.cancelled)return;
                engine.configureThreads(threads);engine.configureHash(hash);
                engine.search(base,moves,budget,job,line->{
                    WdlProbabilities wdl=WdlProbabilities.parse(line,userToMove);
                    if(wdl!=null)ui.post(()->{if(!destroyed&&!paused&&id==revision&&ticket==job)setProbability(wdl);});
                });
                ui.post(()->{if(!destroyed&&id==revision&&ticket==job){probabilityFen=fen;engineBusy=false;ticket=null;}});
            } catch(CancellationException ignored) { }
            catch(Exception e){ui.post(()->{if(!destroyed&&id==revision&&ticket==job){probabilityFen=fen;engineBusy=false;ticket=null;clearProbabilityText();}});}
        });
    }
    private void sendMove() {
        if(paused||game==null||candidateMove==null||candidateAfter==null||latest==null||!game.ourTurn()||(!auto&&!oneShot))return;
        String move=candidateMove;EngineState after=candidateAfter;
        try {
            moveRetry.lock(move,SystemClock.elapsedRealtime());
            game.expect(move,after);
        } catch(Exception e){moveRetry.clear();pause("无法锁定当前着法："+shortError(e));return;}
        candidateMove=null;candidateAfter=null;candidateBasis=null;pendingSince=SystemClock.elapsedRealtime();
        dispatchPendingMove(false);
        oneShot=false;
    }
    private void dispatchPendingMove(boolean retry) {
        if(paused||gestureInFlight||game==null||!game.hasPending()||latest==null)return;
        String move=game.pendingMove();EngineState after=game.pendingState();
        if(move==null||after==null){moveRetry.clear();return;}
        try{moveRetry.lock(move,SystemClock.elapsedRealtime());}
        catch(IllegalStateException e){pause(e.getMessage());return;}
        if(!recovery.stable()||recovery.waitingOpening()||SystemClock.elapsedRealtime()-latestFrameAt>OverlayTiming.FRESH_FRAME_MS||!targetVisible()) {
            retryRecognition("等待清晰棋盘后重试当前着法",SystemClock.elapsedRealtime(),false);return;
        }
        BoardRecognizer.Geometry g=latest.geometry;final int[] taps;
        try{taps=MoveTapPlan.create(game.state,latest,move);}
        catch(IllegalArgumentException e){
            moveRetry.defer(SystemClock.elapsedRealtime());
            retryRecognition("当前着法等待可操作棋盘："+e.getMessage(),SystemClock.elapsedRealtime(),false);return;
        }
        float[] tapX=new float[taps.length],tapY=new float[taps.length];
        for(int i=0;i<taps.length;i++) {
            tapX[i]=g.x(taps[i],game.ownRed);tapY[i]=g.y(taps[i],game.ownRed);
            if(covers(tapX[i],tapY[i])){pause("请把悬浮窗移到棋盘外，再点开始");return;}
            if(tapX[i]<0||tapY[i]<0||tapX[i]>=captureWidth||tapY[i]>=captureHeight){pause("点击坐标超出画面");return;}
        }
        final long id=revision;final BoardRecognizer.Observation actionBasis=latest;
        final int attempt=moveRetry.attempts()+1;gestureInFlight=true;
        show((retry?"重试当前着法":"执行当前着法")+" · 第 "+attempt+" 次\n"+
            (taps.length==1?"已选中棋子 · 点击目标":taps.length==3?"校准选中状态后落子":"点击起点与目标"));
        boolean accepted=XiangqiGestureService.tapSequence(tapX,tapY,fastTaps,
            ()->!destroyed&&!paused&&revision==id&&game!=null&&game.hasPending()&&move.equals(game.pendingMove())&&targetVisible()
                &&latest!=null&&latest.sameInteraction(actionBasis)&&SystemClock.elapsedRealtime()-latestFrameAt<OverlayTiming.FRESH_FRAME_MS,
            ok->{
                if(destroyed||revision!=id)return;
                gestureInFlight=false;
                long now=SystemClock.elapsedRealtime();
                if(!ok) {
                    moveRetry.defer(now);
                    if(!XiangqiGestureService.connected()){pause("点击辅助已关闭，当前着法仍保留");return;}
                    show("当前着法未完成 · 等待识别后重试\n已尝试 "+moveRetry.attempts()+" 次");
                }
                acceptFramesAfter=now;latest=null;recovery.requireFreshFrames();requestFreshFrame();
            });
        if(accepted)moveRetry.dispatched(SystemClock.elapsedRealtime());
        else {
            gestureInFlight=false;moveRetry.defer(SystemClock.elapsedRealtime());
            if(!XiangqiGestureService.connected())pause("请先开启象棋点击辅助");
            else {show("系统未执行当前着法 · 将重新识别并重试");recovery.requireFreshFrames();requestFreshFrame();}
        }
    }
    private void begin(boolean continuous) {
        if(!captureGeometryValid){show("屏幕尺寸不匹配，请关闭助手后重新授权整个屏幕");return;}
        if(!XiangqiGestureService.connected()){show("请先开启象棋点击辅助");return;}
        String active=XiangqiGestureService.activePackage();
        if(active==null||active.equals(getPackageName())||active.startsWith("com.android.")||active.equals("android")) {
            show("请先切换到象棋游戏，再点开始");return;
        }
        if(targetPackage!=null&&!targetPackage.equals(active)){pause("请返回已识别的游戏\n更换游戏请在选项里重新识别");return;}
        targetPackage=active;boundWindow=XiangqiGestureService.windowGeneration();
        cancelSearch();cancelGestures();revision++;paused=false;auto=continuous;oneShot=!continuous;
        recovery.start(game!=null&&game.state!=null&&game.state.finished());updateCaptureRate();
        acceptFramesAfter=SystemClock.elapsedRealtime();latest=null;latestFrameAt=acceptFramesAfter;lastCapturedAt=acceptFramesAfter;
        if(game!=null&&game.hasPending())pendingSince=SystemClock.elapsedRealtime();
        if(game!=null&&game.state==null)refreshState(false);
        show(game==null?"正在识别棋盘与执棋方…":"正在重新核对棋盘…");
        lastSubmitted=0;ui.removeCallbacks(watchdog);ui.post(watchdog);
    }
    private void cancelSearch() {
        if(ticket!=null){ticket.cancelled=true;engine.stop();ticket=null;}
        engineBusy=false;stateLoading=false;candidateMove=null;candidateAfter=null;candidateBasis=null;candidateFrames=0;
    }
    private void cancelGestures(){gestureInFlight=false;XiangqiGestureService.cancelPending();}
    private void pause(String reason) {
        if(destroyed)return;
        paused=true;auto=false;oneShot=false;recovery.stop();updateCaptureRate();revision++;cancelSearch();cancelGestures();show(reason);
    }
    private void resetRecognition(Boolean redToMove) {
        pause("准备重新识别");game=null;moveRetry.clear();targetPackage=null;manualTurn=redToMove;clearProbability();begin(true);
    }
    private final Runnable watchdog=new Runnable() {
        @Override public void run() {
            if(destroyed)return;
            requestFreshFrame();
            if(!paused&&targetPackage!=null) {
                if(lastCapturedAt>0&&SystemClock.elapsedRealtime()-lastCapturedAt>4000)pause("屏幕画面停止更新，请重新启动识别");
                else checkTargetWindow();
            }
            ui.postDelayed(this,captureInterval+20);
        }
    };
    private void requestFreshFrame() {
        // Tiny low-contrast indicator changes request real new capture frames on a still
        // board without flashing the title or reusing an old screenshot as confirmation.
        blink=!blink;if(framePulse!=null)framePulse.setBackgroundColor(blink?0xFF628871:0xFF668C75);
    }
    private void showTurn() {
        if(game==null||game.state==null)return;
        String turn=game.ourTurn()?"轮到我方":"等待对方落子";
        show(side(game.ownRed)+" · "+turn+"\n已跟踪 "+game.moves().size()+" 步"+((!auto&&!oneShot)?" · 自动已暂停":""));
    }
    private String side(boolean red){return red?"我方执红 · 先手":"我方执黑 · 后手";}
    private static String shortError(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s.length()>90?s.substring(0,90):s;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private Button button(String text,Runnable action) {
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(0xFF1D4739);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(3),0,dp(3),0);b.setBackground(background(0xFFEBF1E8,8));
        b.setOnClickListener(v->action.run());return b;
    }
    private void showPanel() {
        panel=new LinearLayout(this);panel.setOrientation(1);panel.setPadding(dp(8),dp(7),dp(8),dp(8));
        panel.setBackground(background(0xFFF9F3E7,14));panel.setElevation(dp(8));
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);
        framePulse=new View(this);LinearLayout.LayoutParams dot=new LinearLayout.LayoutParams(dp(3),dp(3));dot.setMargins(dp(1),0,dp(5),0);title.addView(framePulse,dot);
        TextView handle=new TextView(this);handle.setText("MYX  ·  拖动");handle.setTextColor(0xFF254D3E);handle.setTextSize(13);
        title.addView(handle,new LinearLayout.LayoutParams(0,dp(32),1));
        Button close=button("×",this::stopSelf);title.addView(close,new LinearLayout.LayoutParams(dp(34),dp(32)));panel.addView(title);
        handle.setOnTouchListener(new View.OnTouchListener(){float px,py;int x,y;
            @Override public boolean onTouch(View v,android.view.MotionEvent event){
                if(event.getAction()==MotionEvent.ACTION_DOWN){px=event.getRawX();py=event.getRawY();x=panelParams.x;y=panelParams.y;return true;}
                if(event.getAction()==MotionEvent.ACTION_MOVE){panelParams.x=Math.max(0,Math.min(captureWidth-panel.getWidth(),x+(int)(event.getRawX()-px)));
                    panelParams.y=Math.max(0,Math.min(captureHeight-panel.getHeight(),y+(int)(event.getRawY()-py)));windows.updateViewLayout(panel,panelParams);return true;}
                return true;
            }});
        LinearLayout probabilityRow=new LinearLayout(this);
        for(int i=0;i<probabilityViews.length;i++) {
            TextView value=new TextView(this);probabilityViews[i]=value;
            value.setText(WdlProbabilities.LABELS[i]+" --");value.setTextSize(11);value.setTextColor(0xFF42695A);
            value.setGravity(Gravity.CENTER);value.setMinHeight(dp(22));value.setPadding(0,dp(2),0,dp(2));
            value.setTooltipText("我方视角的引擎胜/和/负估计，不代表保证结果");
            probabilityRow.addView(value,new LinearLayout.LayoutParams(0,-2,1));
        }
        panel.addView(probabilityRow,new LinearLayout.LayoutParams(-1,-2));
        status=new TextView(this);status.setTextColor(0xFF254D3E);status.setTextSize(12);status.setMinHeight(dp(38));status.setMaxLines(2);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setPadding(0,dp(3),0,dp(4));
        panel.addView(status,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout primary=new LinearLayout(this);
        startButton=button("开始",()->begin(true));addRowButton(primary,startButton);
        addRowButton(primary,button("重开",()->resetRecognition(null)));
        foldButton=button("展开",()->{
            collapsed=!collapsed;controls.setVisibility(collapsed?View.GONE:View.VISIBLE);foldButton.setText(collapsed?"展开":"收起");
            panel.post(()->{if(latest!=null)movePanelOutsideBoard(latest.geometry);});
        });addRowButton(primary,foldButton);
        panel.addView(primary);
        controls=new LinearLayout(this);controls.setPadding(0,dp(5),0,0);
        timeButton=button(OverlayTiming.randomLabel(randomMode),this::cycleTime);addRowButton(controls,timeButton);
        timeButton.setTooltipText(OverlayTiming.randomDescription(randomMode));
        addRowButton(controls,button("暂停",()->pause("已暂停 · 点开始继续")));addRowButton(controls,button("选项",this::options));panel.addView(controls);
        collapsed=true;controls.setVisibility(View.GONE);
        panelParams=new WindowManager.LayoutParams(dp(212),WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        panelParams.gravity=Gravity.TOP|Gravity.LEFT;panelParams.x=Math.max(0,captureWidth-dp(224));panelParams.y=dp(27);
        if(Build.VERSION.SDK_INT>=28)panelParams.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        windows.addView(panel,panelParams);show("引擎准备中 · 可先打开游戏");
    }
    private void addRowButton(LinearLayout row,Button button){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(38),1);p.setMargins(dp(2),0,dp(2),0);row.addView(button,p);}
    private void show(String text){message=text;if(status!=null)status.setText(text);if(startButton!=null)startButton.setText(!paused&&auto?"运行中":"开始");}
    private void setProbability(WdlProbabilities wdl){
        for(int i=0;i<probabilityViews.length;i++)if(probabilityViews[i]!=null)probabilityViews[i].setText(WdlProbabilities.LABELS[i]+" "+wdl.percent(i));
    }
    private void clearProbabilityText(){
        for(int i=0;i<probabilityViews.length;i++)if(probabilityViews[i]!=null)probabilityViews[i].setText(WdlProbabilities.LABELS[i]+" --");
    }
    private void clearProbability(){probabilityFen=null;clearProbabilityText();}
    private void showFinishedProbability(){
        if(game==null||game.state==null)return;
        WdlProbabilities result=WdlProbabilities.finished(game.state.result,game.ownRed);
        if(result!=null)setProbability(result);
    }
    private void cycleTime() {
        randomMode=randomMode==1?2:1;
        getSharedPreferences("overlay",0).edit().putInt("random_mode",randomMode).apply();timeButton.setText(OverlayTiming.randomLabel(randomMode));
        timeButton.setTooltipText(OverlayTiming.randomDescription(randomMode));
        if(engineBusy)show("当前思考不变 · 下步使用"+OverlayTiming.randomLabel(randomMode));
    }
    private boolean covers(float x,float y){if(panel==null)return false;int[] p=new int[2];panel.getLocationOnScreen(p);return x>=p[0]&&y>=p[1]&&x<=p[0]+panel.getWidth()&&y<=p[1]+panel.getHeight();}
    private void movePanelOutsideBoard(BoardRecognizer.Geometry g) {
        if(panel==null)return;
        if(panelParams.y+panel.getHeight()>g.top&&panelParams.y<g.bottom) {
            int top=(int)g.top-panel.getHeight()-dp(10);
            if(top>=0){panelParams.y=top;windows.updateViewLayout(panel,panelParams);}
        }
    }
    private GradientDrawable optionShape(int fill,int stroke) {
        GradientDrawable d=background(fill,10);d.setStroke(dp(1),stroke);return d;
    }
    private StateListDrawable optionBackground(boolean danger) {
        int stroke=danger?0xFFB74D45:0xFF6C8C7F;
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},optionShape(danger?0xFFF4DEDC:0xFFDDE9E2,stroke));
        states.addState(new int[]{},optionShape(danger?0xFFFFF5F4:0xFFF7FAF7,stroke));
        return states;
    }
    private Button optionButton(LinearLayout parent,String label,boolean danger,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setGravity(Gravity.CENTER);
        b.setTextColor(danger?0xFF9B2D27:0xFF244B3D);b.setBackground(optionBackground(danger));b.setPadding(dp(10),0,dp(10),0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(5),0,dp(5));parent.addView(b,p);
        b.setOnClickListener(v->action.run());return b;
    }
    private void options() {
        final BoardRecognizer.Observation beforeDialog=latest;
        pause("已暂停 · 选择操作");
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(4),dp(18),dp(10));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("棋盘助手").setView(scroll).create();
        optionButton(box,"搜索线程 · "+searchThreads+"（点击切换）",false,()->{
            searchThreads=searchThreads==1?2:searchThreads==2?4:1;
            searchThreads=Math.min(searchThreads,Math.max(1,Runtime.getRuntime().availableProcessors()));
            getSharedPreferences("overlay",0).edit().putInt("search_threads",searchThreads).apply();
            dialog.dismiss();show("搜索线程："+searchThreads+"\n点开始继续");
        });
        optionButton(box,"搜索缓存 · "+hashMb+" MB（点击切换）",false,()->{
            hashMb=hashMb==128?256:hashMb==256?512:128;
            if(((ActivityManager)getSystemService(ACTIVITY_SERVICE)).isLowRamDevice())hashMb=128;
            getSharedPreferences("overlay",0).edit().putInt("hash_mb",hashMb).apply();
            dialog.dismiss();show("搜索缓存："+hashMb+" MB\n点开始继续");
        });
        optionButton(box,"对手回合预先思考 · "+(prethink?"开":"关"),false,()->{
            prethink=!prethink;probabilityFen=null;
            getSharedPreferences("overlay",0).edit().putBoolean("prethink",prethink).apply();
            dialog.dismiss();show("预先思考"+(prethink?"已开启 · 更耗电、更易发热":"已关闭")+"\n点开始继续");
        });
        optionButton(box,"中途接管 · 现在轮到我方",false,()->{
            dialog.dismiss();
            if(beforeDialog==null){show("未识别到清晰棋盘，请稍等后再试");return;}
            ui.postDelayed(()->resetRecognition(beforeDialog.redBottom),250);
        });
        optionButton(box,"中途接管 · 现在轮到对方",false,()->{
            dialog.dismiss();
            if(beforeDialog==null){show("未识别到清晰棋盘，请稍等后再试");return;}
            ui.postDelayed(()->resetRecognition(!beforeDialog.redBottom),250);
        });
        optionButton(box,fastTaps?"落子节奏 · 快速（点此切换兼容）":"落子节奏 · 兼容（点此切换快速）",false,()->{
            dialog.dismiss();fastTaps=!fastTaps;getSharedPreferences("overlay",0).edit().putBoolean("fast_taps",fastTaps).apply();
            show("落子节奏："+(fastTaps?"快速":"兼容")+"\n点开始继续");
        });
        optionButton(box,"关闭助手",true,()->{dialog.dismiss();stopSelf();});
        optionButton(box,"返回",false,dialog::dismiss);
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);dialog.show();
    }
    @Override public void onDestroy() {
        destroyed=true;instance=null;cancelGestures();
        if(ticket!=null)ticket.cancelled=true;
        engine.close();engineWorker.shutdownNow();visionWorker.shutdownNow();ui.removeCallbacksAndMessages(null);
        if(panel!=null)try{windows.removeView(panel);}catch(Exception ignored){}
        if(display!=null)display.release();if(images!=null)images.close();
        if(projection!=null)try{projection.stop();}catch(Exception ignored){}
        if(captureThread!=null)captureThread.quitSafely();stopForeground(true);super.onDestroy();
    }
}
