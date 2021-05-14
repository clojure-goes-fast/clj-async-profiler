package clj_async_profiler;

import java.util.*;
import static clojure.lang.Compiler.demunge;

public class Helpers {

    public static String safeSubs(String s, int start, int end) {
        int lng = s.length();
        return (start >= 0 && end <= lng && start <= end) ?
            s.substring(start, end) : "";
    }

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
            int slash = s.indexOf("/", frameBeg);
            if (frameBeg > 0)
                sb.append(";");
            if (slash > -1 && slash < frameEnd && dot > frameBeg) {
                // Java or Clojure frame
                String method = s.substring(dot+1, frameEnd);
                if ((method.equals("invoke") ||
                     method.equals("doInvoke") ||
                     method.equals("invokeStatic") ||
                     method.equals("invokePrim")) &&
                    // Exclude things like clojure/lang/Var.invoke
                    !"clojure/lang/".equals(safeSubs(s, frameBeg, frameBeg+13))) {
                    // Clojure frame
                    String frame = s.substring(frameBeg, dot);
                    String newFrame = frame.replace('/', '.');
                    if (frameHasSpecialChar(newFrame)) {
                        if (demungeCache != null) {
                            final String nframe = newFrame;
                            newFrame = demungeCache.computeIfAbsent
                                (nframe, (k) -> clojure.lang.Compiler.demunge(nframe));
                        } else
                            newFrame = clojure.lang.Compiler.demunge(newFrame);
                    } else {
                        newFrame = newFrame.replace('_', '-').replace('$', '/');
                    }
                    sb.append(newFrame);

                    // Check if next frame has the same name and if so, skip it.
                    String possibleNextFrame = frame.concat(".invokeStatic");
                    int nextFrameEnd = Math.min(frameEnd + 1 + possibleNextFrame.length(), lng);
                    if (possibleNextFrame.equals(safeSubs(s, frameEnd+1, nextFrameEnd)))
                        frameBeg = Math.min(nextFrameEnd + 1, lng);
                    else
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
