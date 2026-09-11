package com.yexin.xiangqi;

public final class RetryChecks {
    private static int checks;
    private static void check(boolean c,String m){checks++;if(!c)throw new AssertionError(m);}
    private static void rejected(Runnable r,String m){boolean ok=false;try{r.run();}catch(RuntimeException expected){ok=true;}check(ok,m);}
    public static void main(String[] args){
        MoveRetryPolicy p=new MoveRetryPolicy();
        check(!p.locked()&&p.attempts()==0,"starts unlocked");
        p.lock("b2e2",1000);check(p.locked()&&"b2e2".equals(p.move()),"locks current engine strategy");
        p.lock("b2e2",1100);check("b2e2".equals(p.move()),"same strategy may be reaffirmed");
        rejected(()->p.lock("h2e2",1100),"cannot switch strategy while unfinished");
        check(p.ready(1000),"first dispatch is immediately ready");
        p.dispatched(1000);check(p.attempts()==1&&!p.ready(1259)&&p.ready(1260),"retry delay gates repeat tap");
        p.dispatched(1260);check(p.attempts()==2,"attempt counter increments without changing move");
        p.defer(1300);check(!p.ready(1559)&&p.ready(1560),"transient gesture failure defers but retains move");
        p.clear();check(!p.locked()&&p.move()==null&&p.attempts()==0,"confirmation clears lock");
        p.lock("h2e2",2000);check("h2e2".equals(p.move()),"new strategy allowed only after clear");
        System.out.println("PASS "+checks+" retry checks: strategy lock, bounded redispatch, explicit clear");
    }
}
