/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;

/** Visible, opt-in gesture service. Reads only the active window's package name. */
public final class XiangqiGestureService extends AccessibilityService {
    public interface Guard {boolean allowed();}
    public interface Result {void finished(boolean success);}
    private static volatile XiangqiGestureService instance;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private long gestureGeneration;
    private volatile long windowGeneration;
    @Override protected void onServiceConnected(){instance=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            &&event.getPackageName()!=null&&!getPackageName().contentEquals(event.getPackageName()))windowGeneration++;
    }
    @Override public void onInterrupt(){cancelPending();FloatingService.externalPause("系统暂停了点击辅助");}
    @Override public void onDestroy(){cancelPending();instance=null;FloatingService.externalPause("点击辅助已关闭");super.onDestroy();}
    public static boolean connected(){return instance!=null;}
    public static long windowGeneration(){return instance==null?-1:instance.windowGeneration;}
    public static String activePackage() {
        XiangqiGestureService service=instance;if(service==null)return null;
        AccessibilityNodeInfo node=null;List<AccessibilityWindowInfo> windows=null;
        try {
            // A touched overlay is an "active" accessibility window even though the game
            // retains input focus. Select the focused application, never the floating button.
            windows=service.getWindows();
            for(AccessibilityWindowInfo window:windows)if(window.isFocused()) {
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)return null;
                node=window.getRoot();CharSequence name=node==null?null:node.getPackageName();
                return name==null?null:name.toString();
            }
            if(!windows.isEmpty())return null;
            node=service.getRootInActiveWindow();
            CharSequence name=node==null?null:node.getPackageName();return name==null?null:name.toString();
        } catch(Exception ignored){return null;}
        finally{if(node!=null)node.recycle();if(windows!=null)for(AccessibilityWindowInfo window:windows)window.recycle();}
    }
    public static void cancelPending(){if(instance!=null)instance.gestureGeneration++;}
    public static boolean move(float fromX,float fromY,float toX,float toY,boolean fast,Guard guard,Result result) {
        return tapSequence(new float[]{fromX,toX},new float[]{fromY,toY},fast,guard,result);
    }
    public static boolean tapSequence(float[] x,float[] y,boolean fast,Guard guard,Result result) {
        XiangqiGestureService service=instance;
        if(service==null||x==null||y==null||x.length<1||x.length>3||x.length!=y.length)return false;
        for(int i=0;i<x.length;i++)if(!validPoint(x[i],y[i]))return false;
        if(!guard.allowed())return false;
        final long id=++service.gestureGeneration;
        return service.step(x.clone(),y.clone(),0,fast,id,guard,result);
    }
    private boolean step(float[] x,float[] y,int index,boolean fast,long id,Guard guard,Result result) {
        return tap(x[index],y[index],OverlayTiming.tapDuration(fast),new GestureResultCallback() {
            @Override public void onCancelled(GestureDescription description){result.finished(false);}
            @Override public void onCompleted(GestureDescription description) {
                if(instance!=XiangqiGestureService.this||id!=gestureGeneration){result.finished(false);return;}
                if(index+1==x.length){result.finished(true);return;}
                ui.postDelayed(() -> {
                    if(instance!=XiangqiGestureService.this||id!=gestureGeneration||!guard.allowed()){result.finished(false);return;}
                    boolean accepted=step(x,y,index+1,fast,id,guard,result);
                    if(!accepted)result.finished(false);
                },OverlayTiming.tapGap(fast));
            }
        });
    }
    private boolean tap(float x,float y,int duration,GestureResultCallback callback) {
        if(!validPoint(x,y))return false;
        Path path=new Path();path.moveTo(x,y);
        GestureDescription gesture=new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build();
        try{return dispatchGesture(gesture,callback,ui);}catch(Exception ignored){return false;}
    }
    private static boolean validPoint(float x,float y) {
        return !Float.isNaN(x)&&!Float.isNaN(y)&&!Float.isInfinite(x)&&!Float.isInfinite(y)&&x>=0&&y>=0;
    }
}
