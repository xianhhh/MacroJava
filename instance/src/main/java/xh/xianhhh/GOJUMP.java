package xh.xianhhh;

import xh.xianhhh.macro.ConstExpr;
import xh.xianhhh.macro.GOTO;
import xh.xianhhh.macro.JUMP;

import java.util.Random;

@ConstExpr
public class GOJUMP {

    public static void jumpV(){
        double a;
        JUMP.label("ahead");
        a = (new Random().nextDouble() * Math.random())*2;
        JUMP.label("head");
        try {
            JUMP.goTo("jmp");
            a = 114514;
            if(a >= 0){
                JUMP.goTo("end");
            }else{
                JUMP.goTo("ahead");
            }

        }catch (Exception e){
            e.printStackTrace();
        }
        JUMP.label("jmp");
        if(a <= 1){
            JUMP.goTo("r");
        }
        JUMP.goTo("head");
        JUMP.label("end");
        JUMP.label("r");
        System.out.println("jumpV refM " + a);

    }

    public static void gotoV(){
        int[] arr = {1233,124,1,5,6,1,3,51,25,6,43,12,25,32,3651,2,5,213,1};
        double a = Math.sin(Math.E) - arr.length;
        GOTO.goTo("got");
        System.out.println(a);
        GOTO.label("got");
        System.out.println("gotoV refM " + a);
    }

    public static void jugoV(){
        int[] arrA = {1233,124,1,5,6,1,3,51,25,6,43,12,25,32,3651,2,5,213,1};
        double a = Math.sin(Math.E) - arrA.length;
        GOTO.goTo("jmp");
        System.out.println(a);
        GOTO.label("jmp");
        System.out.println("jugoVJ refM " + a);

        int[] arr = {1233,124,1,5,6,1,3,51,25,6,43,12,25,32,3651,2,5,213,1};
        double r = Math.sin(Math.PI) - arr.length;
        GOTO.goTo("got");
        System.out.println(r);
        GOTO.label("got");
        System.out.println("jotoVG refM " + r);
    }

    public static void main(String[] args){
        jumpV();
        gotoV();
        jugoV();
    }
}
