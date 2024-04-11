package clj_async_profiler;

import java.util.*;
import clojure.lang.Compiler;

public class Helpers {

    // Clojure demunger is slow. If there are no special characters munged in
    // the frame, we can take a faster path.
    public static boolean frameHasSpecialChar(String frame) {
        int lng = frame.length();
        int i = 0;
        while (true) {
            int uscore = frame.indexOf("_", i);
            i = uscore + 1;
            if (uscore == -1)
                return false;
            else if (i < lng && Character.isUpperCase(frame.charAt(i)))
                return true;
        }
    }

    public static String demungeJavaClojureFrames(String s) {
        return demungeJavaClojureFrames(s, null);
    }

    // Hand-rolling "regexps" here for better performance.
    public static String demungeJavaClojureFrames(String s,
                                                  Map<String, String> demungeCache) {
        int lng = s.length();
        StringBuilder sb = new StringBuilder();
        int frameBeg = 0;
        while (frameBeg < lng) {
            int frameEnd = s.indexOf(";", frameBeg);
            if (frameEnd == -1)
                frameEnd = lng;
            int dot = s.lastIndexOf(".", frameEnd);
            if (frameBeg > 0)
                sb.append(";");
            if (dot > frameBeg) {
                // Java or Clojure frame
                int methodLen = frameEnd-dot-1;
                if ((s.regionMatches(dot+1, "invoke", 0, methodLen) ||
                     s.regionMatches(dot+1, "doInvoke", 0, methodLen) ||
                     s.regionMatches(dot+1, "invokeStatic", 0, methodLen) ||
                     s.regionMatches(dot+1, "invokePrim", 0, methodLen)) &&
                    // Exclude things like clojure/lang/Var.invoke
                    !s.regionMatches(frameBeg, "clojure/lang/", 0, 13)) {
                    // Clojure frame
                    String frame = s.substring(frameBeg, dot);
                    String newFrame = frame.replace('/', '.');
                    if (frameHasSpecialChar(newFrame)) {
                        newFrame = (demungeCache != null) ?
                            demungeCache.computeIfAbsent(newFrame, Compiler::demunge) :
                            Compiler.demunge(newFrame);
                    } else {
                        newFrame = newFrame.replace('_', '-').replace('$', '/');
                    }
                    sb.append(newFrame);

                    // Check if next frame has the same name but with Static or
                    // Prim sufix and, if so, skip it.
                    int frameLen = frame.length();
                    int nextFrameNameEnd = frameEnd + 1 + frameLen;
                    if (s.regionMatches(frameEnd+1, frame, 0, frameLen)) {
                        if (s.regionMatches(nextFrameNameEnd, ".invokeStatic", 0, 13))
                            frameBeg = nextFrameNameEnd + 13 + 1;
                        else if (s.regionMatches(nextFrameNameEnd, ".invokePrim", 0, 11))
                            frameBeg = nextFrameNameEnd + 11 + 1;
                        else
                            frameBeg = frameEnd + 1;
                    } else
                        frameBeg = frameEnd + 1;
                } else {
                    // Java frame
                    String frame = s.substring(frameBeg, frameEnd).replace('/', '.');
                    sb.append(frame);
                    frameBeg = frameEnd + 1;
                }
            } else {
                // Other frame
                sb.append(s, frameBeg, frameEnd);
                frameBeg = frameEnd + 1;
            }
        }
        return sb.toString();
    }

    // Terrible emulation of (str/replace s #"(eval|--|__|\.|\$)\d+" "$1") in
    // order to be faster.
    public static String removeLambdaIds(String s) {
        int lng = s.length();
        StringBuilder sb = new StringBuilder(lng);
        int scan = 0, writeFrom = 0, digitIdx, nonDigitIdx;
        while (scan < lng) {
            digitIdx = nonDigitIdx = -1;
            for (int i = scan; i < lng; i++)
                if (Character.isDigit(s.charAt(i))) {
                    digitIdx = i;
                    break;
                }
            if (digitIdx == -1) break;

            for (int i = digitIdx+1; i < lng; i++)
                if (!Character.isDigit(s.charAt(i))) {
                    nonDigitIdx = i;
                    break;
                }
            if (nonDigitIdx == -1) nonDigitIdx = lng;

            if ((digitIdx >= 1 &&
                 s.charAt(digitIdx-1) == '.') ||
                (digitIdx >= 1 &&
                 s.charAt(digitIdx-1) == '$') ||
                (digitIdx >= 2 &&
                 s.charAt(digitIdx-2) == '-' &&
                 s.charAt(digitIdx-1) == '-') ||
                (digitIdx >= 2 &&
                 s.charAt(digitIdx-2) == '_' &&
                 s.charAt(digitIdx-1) == '_') ||
                (digitIdx >= 4 &&
                 s.charAt(digitIdx-4) == 'e' &&
                 s.charAt(digitIdx-3) == 'v' &&
                 s.charAt(digitIdx-2) == 'a' &&
                 s.charAt(digitIdx-1) == 'l')) {
                sb.append(s, writeFrom, digitIdx);
                writeFrom = nonDigitIdx;
            }
            scan = nonDigitIdx;
        }
        return (writeFrom == 0) ? s : sb.append(s, writeFrom, lng).toString();
    }
}
