/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.yexin.xiangqi;

/** Locks one engine move until the screen confirms that exact move. */
public final class MoveRetryPolicy {
    public static final long RETRY_DELAY_MS=260;
    private String move;
    private int attempts;
    private long readyAt;
    public void lock(String value,long now) {
        if(value==null)throw new IllegalArgumentException("缺少当前着法");
        if(move!=null&&!move.equals(value))throw new IllegalStateException("当前着法仍未完成，不能换招");
        if(move==null){move=value;attempts=0;readyAt=now;}
    }
    public boolean locked(){return move!=null;}
    public String move(){return move;}
    public int attempts(){return attempts;}
    public boolean ready(long now){return move!=null&&now>=readyAt;}
    public void dispatched(long now){if(move==null)throw new IllegalStateException("没有锁定着法");attempts++;readyAt=now+RETRY_DELAY_MS;}
    public void defer(long now){if(move!=null)readyAt=Math.max(readyAt,now+RETRY_DELAY_MS);}
    public void clear(){move=null;attempts=0;readyAt=0;}
}
