package xh.xianhhh;

import xh.xianhhh.macro.ConstExpr;
import xh.xianhhh.macro.In;
import xh.xianhhh.macro.Out;
import xh.xianhhh.macro.Ref;


public class InOutRef {
    @ConstExpr
    public static void in(@In int value){
        System.out.println("InOutRef in " + value);
        //value = 114514; Correct Exception
        System.out.println("InOutRef inE " + value);
    }
    @ConstExpr
    public static boolean out(@Out int value){
        //System.out.println("InOutRef out " + value); Correct Exception
        value = 1;
        System.out.println("InOutRef out " + value);
        for(int i = 0; i < 200;i++){
            value += i;
        }
        System.out.println("InOutRef outE " + value + "\n");
        return true;
    }
    @ConstExpr
    public static boolean ref(@Ref int value){
        System.out.println("InOutRef ref " + value);
        value = 114514;
        System.out.println("InOutRef refM " + value);
        for(int i = 0; i < 200;i++){
            value += i;
        }
        System.out.println("InOutRef refE " + value + "\n");
        return true;
    }

    @ConstExpr
    public static void main(String[] args){
        int base = 0;
        in(base);
        System.out.println("Test In " + base + "\n");
        if(out(base)){
            System.out.println("Test Out " + base + "\n");
        }
        if(!ref(base)){
            System.out.println("Test Out " + base + "\n");
        }
        System.out.println("Test Ref " + base + "\n");
        base += 1;
        System.out.println("Main " + base);
    }
}
