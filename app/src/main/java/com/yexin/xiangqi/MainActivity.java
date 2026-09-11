/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class MainActivity extends Activity {
    private static final int BG=0xFFF7F3EB,INK=0xFF233D32,MUTED=0xFF7A8279,GREEN=0xFF2E6655,RED=0xFFAE4837;
    private static final String MODEL_SHA="7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e";
    private static final long MODEL_BYTES=50706378;
    private static final String START_FEN="rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> new Thread(r,"xiangqi-engine"));
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final PikafishClient engine=new PikafishClient();
    private final GameSession game=new GameSession();
    private SharedPreferences prefs;
    private BoardView board;
    private TextView title,status,detail,youSide,opponentSide,history,engineBadge;
    private Button stopButton,undoButton,hintButton,newButton,settingsButton,timeButton;
    private EngineState state;
    private PikafishClient.Ticket ticket;
    private boolean engineReady,foreground,dialogOpen,busy,searchingHint,initialDialog,gameOverAnnounced;
    private volatile boolean destroyed;
    private volatile long generation;
    private int thinkMillis=3000,threads=4,depth;
    private long searchStarted;
    private String lastError;
    private final Pattern depthPattern=Pattern.compile("(?:^| )depth (\\d+)(?: |$)");

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=getSharedPreferences("offline_xiangqi_v1",MODE_PRIVATE);
        thinkMillis=prefs.getInt("think_ms",3000);
        if (thinkMillis!=0 && (thinkMillis<100 || thinkMillis>600000)) thinkMillis=3000;
        threads=Math.max(1,Math.min(4,prefs.getInt("threads",4)));
        initialDialog=!prefs.getBoolean("started",false);
        try { game.restore(prefs.getBoolean("human_red",true),prefs.getString("moves",""),prefs.getString("notes","")); }
        catch (Exception e) { game.reset(true);initialDialog=true; }
        createUi();
        board.show(new EngineState(START_FEN,Collections.emptySet(),"ongoing","none",false),game.humanRed,null);
        status.setText("正在准备皮卡鱼");
        detail.setText("首次启动会在本地展开模型");
        worker.execute(() -> {
            try {
                File model=prepareModel();
                if (destroyed) return;
                File executable=new File(getApplicationInfo().nativeLibraryDir,"libpikafish.so");
                engine.start(executable,model,threads);
                ui.post(() -> {
                    if (destroyed) return;
                    engineReady=true;
                    engineBadge.setText("●  皮卡鱼 · 完全离线");
                    if (foreground && !dialogOpen) refreshPosition(false);
                });
            } catch (Exception e) { ui.post(() -> fail(e)); }
        });
    }

    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int fill,float radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));return d;
    }
    private TextView label(String s,float size,int color) {
        TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setIncludeFontPadding(false);return v;
    }
    private Button button(String s,int fill,int color) {
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(color);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(9),0,dp(9),0);b.setBackground(shape(fill,13));b.setStateListAnimator(null);
        return b;
    }
    private LinearLayout.LayoutParams lp(int w,int h) { return new LinearLayout.LayoutParams(w,h); }
    private void createUi() {
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout safe=new LinearLayout(this);safe.setOrientation(LinearLayout.VERTICAL);safe.setBackgroundColor(BG);
        safe.setOnApplyWindowInsetsListener((v,insets) -> {
            if (Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(b.left,b.top,b.right,b.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(safe);safe.requestApplyInsets();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(10),dp(16),dp(10));
        safe.addView(root,lp(-1,-1));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading=new LinearLayout(this);heading.setOrientation(LinearLayout.VERTICAL);
        title=label("离线象棋",26,INK);title.setTypeface(null,Typeface.BOLD);heading.addView(title);
        engineBadge=label("●  皮卡鱼 · 准备中",11,GREEN);LinearLayout.LayoutParams b=lp(-2,-2);b.topMargin=dp(6);heading.addView(engineBadge,b);
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        settingsButton=button("设置",0xFFE9EEE6,GREEN);settingsButton.setContentDescription("思考时间、性能与关于");
        settingsButton.setOnClickListener(v -> settingsDialog());header.addView(settingsButton,lp(dp(62),dp(43)));
        root.addView(header,lp(-1,dp(62)));
        LinearLayout statusBox=new LinearLayout(this);statusBox.setOrientation(LinearLayout.VERTICAL);statusBox.setPadding(dp(14),dp(12),dp(14),dp(12));statusBox.setBackground(shape(0xFFFDFBF6,17));
        LinearLayout sides=new LinearLayout(this);sides.setGravity(Gravity.CENTER_VERTICAL);
        opponentSide=label("皮卡鱼  执黑 · 后手",12,MUTED);sides.addView(opponentSide,new LinearLayout.LayoutParams(0,-2,1));
        youSide=label("你  执红 · 先手",12,RED);sides.addView(youSide,lp(-2,-2));statusBox.addView(sides,lp(-1,-2));
        LinearLayout progressRow=new LinearLayout(this);progressRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout statusColumn=new LinearLayout(this);statusColumn.setOrientation(LinearLayout.VERTICAL);
        status=label("正在准备棋盘",18,INK);status.setTypeface(null,Typeface.BOLD);statusColumn.addView(status);
        detail=label("",11,MUTED);detail.setSingleLine(true);LinearLayout.LayoutParams dl=lp(-1,-2);dl.topMargin=dp(6);statusColumn.addView(detail,dl);
        progressRow.addView(statusColumn,new LinearLayout.LayoutParams(0,-2,1));
        stopButton=button("立即落子",GREEN,Color.WHITE);stopButton.setTextSize(12);stopButton.setOnClickListener(v -> {
            if (ticket!=null) { ticket.stopNow=true;engine.stop();stopButton.setEnabled(false);detail.setText("正在结束思考…"); }
        });
        LinearLayout.LayoutParams st=lp(dp(80),dp(39));st.leftMargin=dp(8);progressRow.addView(stopButton,st);stopButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams pr=lp(-1,-2);pr.topMargin=dp(11);statusBox.addView(progressRow,pr);
        LinearLayout.LayoutParams sb=lp(-1,-2);sb.topMargin=dp(10);sb.bottomMargin=dp(5);root.addView(statusBox,sb);
        board=new BoardView(this);board.setTap(this::boardTap);root.addView(board,new LinearLayout.LayoutParams(-1,0,1));
        history=label("开局就绪 · 点选棋子，再点落点",12,MUTED);history.setSingleLine(true);history.setGravity(Gravity.CENTER_VERTICAL);
        history.setEllipsize(android.text.TextUtils.TruncateAt.START);root.addView(history,lp(-1,dp(32)));
        LinearLayout actions=new LinearLayout(this);
        newButton=button("新对局",GREEN,Color.WHITE);newButton.setOnClickListener(v -> newGameDialog());
        undoButton=button("悔棋",0xFFE8EBDD,INK);undoButton.setOnClickListener(v -> undo());
        hintButton=button("提示",0xFFE8EBDD,INK);hintButton.setOnClickListener(v -> hint());
        Button[] a={newButton,undoButton,hintButton};
        for (int i=0;i<a.length;i++) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);if(i>0)p.leftMargin=dp(9);actions.addView(a[i],p); }
        root.addView(actions,lp(-1,-2));
        timeButton=button("每步思考 3 秒  ▾",BG,MUTED);timeButton.setTextSize(12);timeButton.setOnClickListener(v -> settingsDialog());
        LinearLayout.LayoutParams tl=lp(-1,dp(37));tl.topMargin=dp(4);root.addView(timeButton,tl);
        updateControls();
    }

    private File prepareModel() throws Exception {
        File dir=new File(getFilesDir(),"engine");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法准备本地模型目录");
        File destination=new File(dir,"pikafish.nnue");
        // The app's private file can be reused after its first full SHA-256 verification.
        if (destination.length()==MODEL_BYTES && prefs.getString("model_sha","").equals(MODEL_SHA)) return destination;
        File temp=new File(dir,"pikafish.nnue.tmp");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=getAssets().open("engine/pikafish.nnue");FileOutputStream out=new FileOutputStream(temp)) {
            byte[] buffer=new byte[262144];int n;
            while((n=in.read(buffer))!=-1) { out.write(buffer,0,n);digest.update(buffer,0,n); }
            out.getFD().sync();
        }
        StringBuilder hash=new StringBuilder();for(byte value:digest.digest()) hash.append(String.format(Locale.ROOT,"%02x",value&255));
        if (!MODEL_SHA.equals(hash.toString()) || temp.length()!=MODEL_BYTES) throw new IOException("模型校验失败，请重新安装完整 APK");
        if (!temp.renameTo(destination)) throw new IOException("无法保存本地模型");
        prefs.edit().putString("model_sha",MODEL_SHA).apply();
        return destination;
    }

    private long cancelWork() {
        generation++;
        if(ticket!=null)ticket.cancelled=true;
        engine.stop();ticket=null;busy=false;searchingHint=false;
        ui.removeCallbacks(tick);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        return generation;
    }
    private void refreshPosition(boolean resetEngine) {
        if (!engineReady || !foreground || dialogOpen || destroyed) { updateControls();return; }
        long id=cancelWork();busy=true;state=null;lastError=null;
        status.setText("正在载入局面");detail.setText("本地计算中");updateControls();
        List<String> moves=game.moves();
        worker.execute(() -> {
            try {
                if(id!=generation)return;
                if(resetEngine)engine.newGame();
                EngineState next=engine.state(moves);
                ui.post(() -> {
                    if(!valid(id))return;
                    state=next;busy=false;board.show(state,game.humanRed,game.lastMove());updateHistory();showTurn();
                    if(initialDialog) {initialDialog=false;newGameDialog();}
                    else maybeStartAi();
                });
            } catch(Exception e) { ui.post(() -> {if(valid(id))fail(e);}); }
        });
    }
    private boolean valid(long id) { return id==generation && !destroyed && foreground; }
    private void maybeStartAi() {
        if(engineReady && foreground && !dialogOpen && !busy && state!=null && !state.finished() && !game.humanTurn()) startSearch(false);
    }
    private void startSearch(boolean asHint) {
        if(state==null || state.finished() || busy || !engineReady)return;
        long id=++generation;
        PikafishClient.Ticket job=new PikafishClient.Ticket();ticket=job;
        busy=true;searchingHint=asHint;depth=0;searchStarted=SystemClock.elapsedRealtime();
        EngineState before=state;List<String> moves=game.moves();int budget=asHint?(thinkMillis==0?5000:Math.min(thinkMillis,5000)):thinkMillis;
        int threadCount=threads;
        board.select(-1);board.clearHint();
        status.setText(asHint?"皮卡鱼正在找好棋":"皮卡鱼正在思考");
        detail.setText("正在开始搜索…");stopButton.setEnabled(true);updateControls();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);ui.post(tick);
        worker.execute(() -> {
            try {
                if(job.cancelled)return;
                engine.configureThreads(threadCount);
                final long[] progressAt={0};
                String move=engine.search(moves,budget,job,line -> {
                    long now=SystemClock.elapsedRealtime();
                    if(now-progressAt[0]<180)return;
                    progressAt[0]=now;
                    Matcher matcher=depthPattern.matcher(line);
                    if(matcher.find()) {int d=Integer.parseInt(matcher.group(1));ui.post(() -> {if(valid(id))depth=d;});}
                });
                if(!before.legalMoves.contains(move))throw new IOException("引擎返回了无效着法，请重新载入对局");
                ui.post(() -> {
                    if(!valid(id) || job.cancelled)return;
                    busy=false;searchingHint=false;ticket=null;ui.removeCallbacks(tick);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if(asHint) {board.showHint(move);showTurn();detail.setText("建议："+before.position.notation(move)+" · 绿色箭头所示");}
                    else {game.append(move,before);save();refreshPosition(false);}
                });
            } catch(CancellationException ignored) { }
            catch(Exception e) {ui.post(() -> {if(valid(id))fail(e);});}
        });
    }
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(destroyed || !foreground || !busy || ticket==null)return;
            double seconds=(SystemClock.elapsedRealtime()-searchStarted)/1000.0;
            String s=String.format(Locale.CHINA,"已思考 %.1f 秒",seconds);
            if(depth>0)s+=" · 搜索深度 "+depth;
            if(ticket.stopNow)s="正在结束思考…";
            detail.setText(s);ui.postDelayed(this,250);
        }
    };

    private void boardTap(int square) {
        if(state==null || state.finished() || !game.humanTurn() || dialogOpen || !foreground || busy&&!searchingHint)return;
        char piece=state.position.squares[square];int selected=board.selected();
        if(selected>=0 && selected!=square) {
            String move=Position.coordinate(selected)+Position.coordinate(square);
            if(state.legalMoves.contains(move)) {
                EngineState before=state;cancelWork();game.append(move,before);save();refreshPosition(false);return;
            }
        }
        if(piece!='.' && Position.isRed(piece)==game.humanRed)board.select(selected==square?-1:square);
        else if(selected>=0)Toast.makeText(this,"请选择绿色标记的合法落点",Toast.LENGTH_SHORT).show();
    }
    private void hint() {
        if(busy && searchingHint) {if(ticket!=null){ticket.stopNow=true;engine.stop();}return;}
        if(state!=null && game.humanTurn() && !state.finished() && !busy)startSearch(true);
    }
    private void undo() {
        if(!game.canUndo())return;
        cancelWork();game.undo();gameOverAnnounced=false;save();refreshPosition(false);
    }
    private void showTurn() {
        if(state==null) {updateControls();return;}
        if(state.finished()) {
            status.setText(state.resultText());detail.setText(state.reasonText());
            if(!gameOverAnnounced) {gameOverAnnounced=true;board.announceForAccessibility(state.resultText());}
        } else if(game.humanTurn()) {
            status.setText(state.check?"你被将军了，请应将":"轮到你走棋");
            detail.setText(state.check?"只能选择能解除将军的着法":"点选棋子，绿色标记为合法落点");
        } else {
            status.setText("轮到皮卡鱼走棋");detail.setText("即将开始本地搜索");
        }
        updateControls();
    }
    private String timeLabel() {
        if(thinkMillis==0)return "手动结束";
        return thinkMillis%1000==0 ? thinkMillis/1000+" 秒" : String.format(Locale.CHINA,"%.1f 秒",thinkMillis/1000.0);
    }
    private void updateControls() {
        if(youSide==null)return;
        youSide.setText(game.humanRed?"你  执红 · 先手":"你  执黑 · 后手");youSide.setTextColor(game.humanRed?RED:INK);
        opponentSide.setText(game.humanRed?"皮卡鱼  执黑 · 后手":"皮卡鱼  执红 · 先手");
        opponentSide.setTextColor(game.humanRed?MUTED:RED);
        undoButton.setEnabled(engineReady && game.canUndo() && !dialogOpen);
        hintButton.setEnabled(engineReady && state!=null && !state.finished() && game.humanTurn() && (!busy||searchingHint) && !dialogOpen);
        hintButton.setText(searchingHint?"结束提示":"提示");
        timeButton.setText("每步思考 "+timeLabel()+"  ▾");
        stopButton.setVisibility(busy && ticket!=null ? View.VISIBLE : View.GONE);
        stopButton.setText(searchingHint?"结束提示":"立即落子");
        undoButton.setAlpha(undoButton.isEnabled()?1:.42f);hintButton.setAlpha(hintButton.isEnabled()?1:.42f);
    }
    private void updateHistory() {
        List<String> notes=game.notes();
        if(notes.isEmpty()){history.setText("开局就绪 · "+(game.humanRed?"你执红先行":"AI 执红先行"));return;}
        int begin=Math.max(0,notes.size()-3);StringBuilder s=new StringBuilder();
        for(int i=begin;i<notes.size();i++) {
            if(s.length()>0)s.append("   ");
            s.append(i/2+1).append((i&1)==0?". ":"… ").append(notes.get(i));
        }
        history.setText(s);
    }
    private void save() {
        prefs.edit().putBoolean("started",true).putBoolean("human_red",game.humanRed)
            .putString("moves",game.serializeMoves()).putString("notes",game.serializeNotes())
            .putInt("think_ms",thinkMillis).putInt("threads",threads).apply();
    }

    private LinearLayout dialogContent() {
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(24),dp(8),dp(24),dp(2));return l;
    }
    private void pauseForDialog() {cancelWork();dialogOpen=true;updateControls();}
    private void resumeAfterDialog() {dialogOpen=false;if(!destroyed && foreground && engineReady)refreshPosition(false);}
    private void newGameDialog() {
        if(dialogOpen)return;
        initialDialog=false;
        pauseForDialog();
        LinearLayout content=dialogContent();
        TextView help=label("选择你执哪一方，双方始终红棋先行。",14,MUTED);help.setLineSpacing(dp(3),1);content.addView(help);
        RadioGroup side=new RadioGroup(this);side.setPadding(0,dp(14),0,dp(8));
        RadioButton humanFirst=new RadioButton(this);humanFirst.setId(101);humanFirst.setText("我先手  ·  我执红，AI 执黑");humanFirst.setTextSize(15);
        RadioButton aiFirst=new RadioButton(this);aiFirst.setId(102);aiFirst.setText("AI 先手  ·  AI 执红，我执黑");aiFirst.setTextSize(15);
        side.addView(humanFirst,lp(-1,dp(49)));side.addView(aiFirst,lp(-1,dp(49)));side.check(game.humanRed?101:102);content.addView(side);
        content.addView(label("每步思考："+timeLabel()+"，可在设置中调整。",12,MUTED));
        if(game.size()>0) {TextView t=label("开始后会替换当前对局。",12,RED);LinearLayout.LayoutParams l=lp(-1,-2);l.topMargin=dp(12);content.addView(t,l);}
        AlertDialog d=new AlertDialog.Builder(this).setTitle("开始新对局").setView(content)
            .setNegativeButton("取消",null).setPositiveButton("开始",(v,w) -> {
                cancelWork();game.reset(side.getCheckedRadioButtonId()==101);state=null;gameOverAnnounced=false;save();
                board.show(new EngineState(START_FEN,Collections.emptySet(),"ongoing","none",false),game.humanRed,null);
            }).create();
        d.setOnDismissListener(v -> resumeAfterDialog());d.show();
    }
    private void settingsDialog() {
        if(dialogOpen)return;pauseForDialog();
        LinearLayout content=dialogContent();
        content.addView(label("每步思考时间",14,INK));
        final int[] times={1000,3000,5000,10000,30000,60000,0,-1};
        final String[] labels={"1 秒 · 快速","3 秒 · 默认","5 秒","10 秒","30 秒","60 秒","手动结束 · 点击立即落子","自定义秒数"};
        Spinner time=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);time.setAdapter(adapter);
        int selected=7;for(int i=0;i<times.length;i++)if(times[i]==thinkMillis)selected=i;time.setSelection(selected);
        content.addView(time,lp(-1,dp(50)));
        EditText custom=new EditText(this);custom.setSingleLine();custom.setText(thinkMillis==0?"30":Integer.toString(Math.max(1,thinkMillis/1000)));custom.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);custom.setHint("1～600 秒");custom.setTextSize(15);content.addView(custom,lp(-1,dp(49)));
        custom.setVisibility(selected==7?View.VISIBLE:View.GONE);
        time.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> p){}
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int i,long id){custom.setVisibility(i==7?View.VISIBLE:View.GONE);}
        });
        TextView performance=label("计算性能",14,INK);LinearLayout.LayoutParams pl=lp(-1,-2);pl.topMargin=dp(17);content.addView(performance,pl);
        Spinner power=new Spinner(this);power.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"省电 · 1 线程","均衡 · 2 线程","高性能 · 4 线程"}));power.setSelection(threads==1?0:threads==2?1:2);content.addView(power,lp(-1,dp(50)));
        TextView note=label("思考越久通常能搜索得越深，也会增加耗电和发热。切到后台时自动暂停。",12,MUTED);note.setLineSpacing(dp(3),1);content.addView(note);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("对弈设置").setView(content).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        d.setOnDismissListener(v -> resumeAfterDialog());
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int index=time.getSelectedItemPosition();int ms=times[index];
            if(ms==-1){try{int secs=Integer.parseInt(custom.getText().toString());if(secs<1||secs>600)throw new NumberFormatException();ms=secs*1000;}catch(Exception e){custom.setError("请输入 1～600 的整数");return;}}
            thinkMillis=ms;threads=new int[]{1,2,4}[power.getSelectedItemPosition()];save();d.dismiss();
        });
    }
    private void fail(Exception e) {
        if(destroyed)return;cancelWork();lastError=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
        status.setText("暂时无法继续对局");detail.setText(lastError);updateControls();
        new AlertDialog.Builder(this).setTitle("引擎提示").setMessage(lastError+"\n\n当前进度已保留。可重开对局或关闭后重新打开应用。")
            .setPositiveButton("知道了",null).show();
    }
    @Override protected void onResume() {
        super.onResume();foreground=true;
        if(engineReady && !dialogOpen && !busy)refreshPosition(false);
    }
    @Override protected void onStop() {
        foreground=false;cancelWork();save();super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed=true;cancelWork();engine.close();worker.shutdownNow();ui.removeCallbacksAndMessages(null);super.onDestroy();
    }
}
