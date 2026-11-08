package lockon;

import java.util.ArrayList;
import java.util.List;

/** Near-to-far chunk order; a straight scouting heading never circles home. */
final class SearchPlan {
    record Offset(int x,int z) {}
    static List<Offset> chunks(int radius) {
        var out=new ArrayList<Offset>();
        out.add(new Offset(0,0));
        for(int r=1;r<=radius;r++) {
            for(int x=-r;x<=r;x++) {out.add(new Offset(x,-r));out.add(new Offset(x,r));}
            for(int z=-r+1;z<r;z++) {out.add(new Offset(-r,z));out.add(new Offset(r,z));}
        }
        return List.copyOf(out);
    }
}
