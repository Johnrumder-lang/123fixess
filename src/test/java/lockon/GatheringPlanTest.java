package lockon;

public final class GatheringPlanTest {
    private static int checks;
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        var chunks=SearchPlan.chunks(32);
        check(chunks.size()==65*65,"32-chunk radius covers every offset");
        check(new java.util.HashSet<>(chunks).size()==chunks.size(),"scan never revisits duplicate offsets");
        int radius=0;
        for(var p:chunks){
            int r=Math.max(Math.abs(p.x()),Math.abs(p.z()));
            check(r>=radius,"scan processes nearer chunk rings first");radius=r;
        }
        check(chunks.contains(new SearchPlan.Offset(32,0)),"search reaches 512 blocks east");
        check(chunks.contains(new SearchPlan.Offset(-32,0)),"search reaches 512 blocks west");
        ProgressWatch progress=new ProgressWatch();
        check(!progress.stalled("first",5,0),"new waypoint starts progress window");
        for(int tick=1;tick<35;tick++)
            check(!progress.stalled("first",5+(tick%2)*0.2,tick),"brief position jitter allowed");
        check(progress.stalled("first",5,35),"back-and-forth movement is detected as stalled");
        check(!progress.stalled("second",6,36),"new route gets a fresh progress window");
        check(!progress.stalled("second",5,65),"actual forward progress extends deadline");
        check(!progress.stalled("second",4,90),"continued progress is not stuck");
        progress.reset();
        check(!progress.stalled("second",4,500),"restart clears stale deadline");
        System.out.println("Gathering search/progress: "+checks+" checks passed");
    }
}
