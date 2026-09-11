/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

import android.content.Context;
import java.io.*;
import java.security.MessageDigest;

public final class EngineFiles {
    private static final String SHA="7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e";
    public static synchronized File model(Context context) throws Exception {
        File dir=new File(context.getFilesDir(),"engine");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建模型目录");
        File result=new File(dir,"pikafish.nnue");
        if(result.length()==50706378L&&SHA.equals(context.getSharedPreferences("overlay",0).getString("model_verified_sha","")))return result;
        if(result.length()==50706378L&&SHA.equals(hash(result))) {
            context.getSharedPreferences("overlay",0).edit().putString("model_verified_sha",SHA).apply();return result;
        }
        File temp=new File(dir,"overlay-model.tmp");
        try(InputStream in=context.getAssets().open("engine/pikafish.nnue");FileOutputStream out=new FileOutputStream(temp)) {
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.getFD().sync();
        }
        if(temp.length()!=50706378L||!SHA.equals(hash(temp)))throw new IOException("模型校验失败");
        if(!temp.renameTo(result))throw new IOException("无法安装离线模型");
        context.getSharedPreferences("overlay",0).edit().putString("model_verified_sha",SHA).apply();return result;
    }
    private static String hash(File file) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) {
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);
        }
        StringBuilder out=new StringBuilder();for(byte b:md.digest())out.append(String.format("%02x",b&255));return out.toString();
    }
    public static File executable(Context c){return new File(c.getApplicationInfo().nativeLibraryDir,"libpikafish.so");}
    private EngineFiles(){}
}
