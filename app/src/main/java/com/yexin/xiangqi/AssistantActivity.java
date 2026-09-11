/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public final class AssistantActivity extends Activity {
    private Button overlayButton,accessButton,startButton;
    private boolean notificationAsked;
    private static final int GREEN=0xFF295E4D,INK=0xFF233C32;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFFF6F1E7);getWindow().setNavigationBarColor(0xFFF6F1E7);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xFFF6F1E7);
        LinearLayout body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(22),dp(24),dp(22),dp(24));
        scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            if(Build.VERSION.SDK_INT>=30){Insets i=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(i.left,i.top,i.right,i.bottom);}
            else view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        text(body,"MYX",36,INK,0);
        text(body,"悬浮象棋助手",22,INK,5);
        text(body,"按顺序完成以下步骤",16,GREEN,22);
        overlayButton=button(body,"① 允许悬浮窗",()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
        accessButton=button(body,"② 开启点击辅助",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        startButton=button(body,"③ 启动屏幕识别",this::startCapture);
        text(body,"启动后切换到象棋游戏，进入完整棋盘，在悬浮窗点「开始」。",14,INK,18);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void text(LinearLayout parent,String value,int size,int color,int top) {
        TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(3),1);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(view,p);
    }
    private Button button(LinearLayout parent,String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setAllCaps(false);
        GradientDrawable bg=new GradientDrawable();bg.setColor(GREEN);bg.setCornerRadius(dp(12));b.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(51));p.topMargin=dp(13);parent.addView(b,p);
        b.setOnClickListener(v->action.run());return b;
    }
    @Override public void onResume(){super.onResume();refresh();}
    private void refresh() {
        overlayButton.setText(Settings.canDrawOverlays(this)?"① 悬浮窗已允许":"① 允许悬浮窗");
        accessButton.setText(XiangqiGestureService.connected()?"② 点击辅助已开启":"② 开启点击辅助");
        startButton.setText(FloatingService.running()?"③ 助手已启动":"③ 启动屏幕识别");
    }
    private void startCapture() {
        if(FloatingService.running()){new AlertDialog.Builder(this).setMessage("切换到象棋游戏，在悬浮窗点「开始」。").setPositiveButton("知道了",null).show();return;}
        if(!Settings.canDrawOverlays(this)||!XiangqiGestureService.connected()) {
            new AlertDialog.Builder(this).setMessage("请先允许悬浮窗并开启象棋点击辅助。").setPositiveButton("知道了",null).show();return;
        }
        if(Build.VERSION.SDK_INT>=33&&!notificationAsked&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            notificationAsked=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},22);return;
        }
        requestCapture();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);if(request==22)requestCapture();
    }
    private void requestCapture() {
        MediaProjectionManager manager=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();
        startActivityForResult(intent,31);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==31&&result==RESULT_OK&&data!=null) {
            Intent start=new Intent(this,FloatingService.class).putExtra("resultCode",result).putExtra("projection",data);
            try{startForegroundService(start);Toast.makeText(this,"请切换到象棋对局，在悬浮窗点开始",Toast.LENGTH_LONG).show();}
            catch(Exception e){Toast.makeText(this,"无法启动屏幕识别，请重新授权",Toast.LENGTH_LONG).show();}
        }
    }
}
