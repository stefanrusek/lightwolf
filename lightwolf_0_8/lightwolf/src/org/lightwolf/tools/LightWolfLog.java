package org.lightwolf.tools;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Formatter;

public class LightWolfLog {

    private static boolean atNewLine = true;

    public static void print(String text) {
        synchronized(System.out) {
            int st = 0;
            while (st < text.length()) {
                if (atNewLine) {
                    System.out.print("[lightwolf] ");
                    atNewLine = false;
                }
                int nlPos = text.indexOf('\n', st);
                if (nlPos == -1) {
                    System.out.print(text.substring(st));
                    return;
                }
                ++nlPos;
                System.out.print(text.substring(st, nlPos));
                atNewLine = true;
                st = nlPos;
            }
        }
    }

    public static void println() {
        print("\n");
    }

    public static void println(String text) {
        synchronized(System.out) {
            print(text);
            print("\n");
        }
    }

    public static void println(Object object) {
        if (object == null) {
            print("null\n");
        } else {
            synchronized(System.out) {
                print(object.toString());
                print("\n");
            }
        }
    }

    public static void printTrace(Throwable e) {
        StringWriter sw = new StringWriter(500);
        PrintWriter pw = new PrintWriter(sw, false);
        e.printStackTrace(pw);
        pw.flush();
        print(sw.toString());
    }

    public static void printf(String text, Object... args) {
        StringBuilder sb = new StringBuilder(text.length());
        new Formatter(sb).format(text, args);
        print(sb.toString());
    }

}
