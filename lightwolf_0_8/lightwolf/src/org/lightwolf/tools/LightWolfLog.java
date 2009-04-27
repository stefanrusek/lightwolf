/*
 * Copyright (c) 2007, Fernando Colombo. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1) Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 
 * 2) Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED ''AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
        pw.print(Thread.currentThread());
        pw.print(' ');
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
