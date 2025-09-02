/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package clj_async_profiler.convert;

import clj_async_profiler.jfr.ClassRef;
import clj_async_profiler.jfr.Dictionary;
import clj_async_profiler.jfr.JfrReader;
import clj_async_profiler.jfr.MethodRef;
import clj_async_profiler.jfr.event.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Map;

import static clj_async_profiler.convert.Frame.*;

public abstract class JfrConverter {
    public final JfrReader jfr;
    protected final EventCollector collector;
    protected Dictionary<String> methodNames;

    public JfrConverter(JfrReader jfr) {
        this.jfr = jfr;

        EventCollector collector = createCollector();
        this.collector = collector;
    }

    public void convert() throws IOException {
        jfr.stopAtNewChunk = true;

        while (jfr.hasMoreChunks()) {
            // Reset method dictionary, since new chunk may have different IDs
            methodNames = new Dictionary<>();

            collector.beforeChunk();
            collectEvents();
            collector.afterChunk();

            convertChunk();
        }

        if (collector.finish()) {
            convertChunk();
        }
    }

    protected EventCollector createCollector() {
        return new EventAggregator(false, 0.0);
    }

    protected void collectEvents() throws IOException {
        Class<? extends Event> eventClass = ExecutionSample.class;

        BitSet threadStates = null;
        long startTicks = Long.MIN_VALUE;
        long endTicks = Long.MAX_VALUE;

        for (Event event; (event = jfr.readEvent(eventClass)) != null; ) {
            if (event.time >= startTicks && event.time <= endTicks) {
                if (threadStates == null || threadStates.get(((ExecutionSample) event).threadState)) {
                    collector.collect(event);
                }
            }
        }
    }

    protected void convertChunk() {
        // To be overridden in subclasses
    }

    protected int toThreadState(String name) {
        Map<Integer, String> threadStates = jfr.enums.get("jdk.types.ThreadState");
        if (threadStates != null) {
            for (Map.Entry<Integer, String> entry : threadStates.entrySet()) {
                if (entry.getValue().startsWith(name, 6)) {
                    return entry.getKey();
                }
            }
        }
        throw new IllegalArgumentException("Unknown thread state: " + name);
    }

    protected BitSet getThreadStates(boolean cpu) {
        BitSet set = new BitSet();
        Map<Integer, String> threadStates = jfr.enums.get("jdk.types.ThreadState");
        if (threadStates != null) {
            for (Map.Entry<Integer, String> entry : threadStates.entrySet()) {
                set.set(entry.getKey(), "STATE_DEFAULT".equals(entry.getValue()) == cpu);
            }
        }
        return set;
    }

    // millis can be an absolute timestamp or an offset from the beginning/end of the recording
    protected long toTicks(long millis) {
        long nanos = millis * 1_000_000;
        if (millis < 0) {
            nanos += jfr.endNanos;
        } else if (millis < 1500000000000L) {
            nanos += jfr.startNanos;
        }
        return (long) ((nanos - jfr.chunkStartNanos) * (jfr.ticksPerSec / 1e9)) + jfr.chunkStartTicks;
    }

    public String getMethodName(long methodId, byte methodType) {
        String result = methodNames.get(methodId);
        if (result == null) {
            methodNames.put(methodId, result = resolveMethodName(methodId, methodType));
        }
        return result;
    }

    private String resolveMethodName(long methodId, byte methodType) {
        MethodRef method = jfr.methods.get(methodId);
        if (method == null) {
            return "unknown";
        }

        ClassRef cls = jfr.classes.get(method.cls);
        byte[] className = jfr.symbols.get(cls.name);
        byte[] methodName = jfr.symbols.get(method.name);

        if (className == null || className.length == 0 || isNativeFrame(methodType)) {
            return new String(methodName, StandardCharsets.UTF_8);
        } else {
            String classStr = toJavaClassName(className, 0, false);
            if (methodName == null || methodName.length == 0) {
                return classStr;
            }
            String methodStr = new String(methodName, StandardCharsets.UTF_8);
            return classStr + '.' + methodStr;
        }
    }

    public String getClassName(long classId) {
        ClassRef cls = jfr.classes.get(classId);
        if (cls == null) {
            return "null";
        }
        byte[] className = jfr.symbols.get(cls.name);

        int arrayDepth = 0;
        while (className[arrayDepth] == '[') {
            arrayDepth++;
        }

        String name = toJavaClassName(className, arrayDepth, true);
        while (arrayDepth-- > 0) {
            name = name.concat("[]");
        }
        return name;
    }

    private String toJavaClassName(byte[] symbol, int start, boolean dotted) {
        int end = symbol.length;
        if (start > 0) {
            switch (symbol[start]) {
                case 'B':
                    return "byte";
                case 'C':
                    return "char";
                case 'S':
                    return "short";
                case 'I':
                    return "int";
                case 'J':
                    return "long";
                case 'Z':
                    return "boolean";
                case 'F':
                    return "float";
                case 'D':
                    return "double";
                case 'L':
                    start++;
                    end--;
            }
        }

        String s = new String(symbol, start, end - start, StandardCharsets.UTF_8);
        return dotted ? s.replace('/', '.') : s;
    }

    public StackTraceElement getStackTraceElement(long methodId, byte methodType, int location) {
        MethodRef method = jfr.methods.get(methodId);
        if (method == null) {
            return new StackTraceElement("", "unknown", null, 0);
        }

        ClassRef cls = jfr.classes.get(method.cls);
        byte[] className = jfr.symbols.get(cls.name);
        byte[] methodName = jfr.symbols.get(method.name);

        String classStr = className == null || className.length == 0 || isNativeFrame(methodType) ? "" :
                toJavaClassName(className, 0, false);
        String methodStr = methodName == null || methodName.length == 0 ? "" :
                new String(methodName, StandardCharsets.UTF_8);
        return new StackTraceElement(classStr, methodStr, null, location >>> 16);
    }

    public String getThreadName(int tid) {
        String threadName = jfr.threads.get(tid);
        return threadName == null ? "[tid=" + tid + ']' :
                threadName.startsWith("[tid=") ? threadName : '[' + threadName + " tid=" + tid + ']';
    }

    protected boolean isNativeFrame(byte methodType) {
        // In JDK Flight Recorder, TYPE_NATIVE denotes Java native methods,
        // while in async-profiler, TYPE_NATIVE is for C methods
        return methodType == TYPE_NATIVE && jfr.getEnumValue("jdk.types.FrameType", TYPE_KERNEL) != null ||
                methodType == TYPE_CPP ||
                methodType == TYPE_KERNEL;
    }

}
