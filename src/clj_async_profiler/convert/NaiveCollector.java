package clj_async_profiler.convert;

import java.lang.reflect.Array;
import java.util.*;
import clj_async_profiler.jfr.*;

public class NaiveCollector {

    public static final int BLOCK_DURATION_MS = 10;

    public HashMap<Long, String> traceIdToStack = new HashMap<>();
    public HashSet<String> stacktraceNames = new HashSet<>();
    public HashMap<String, Long> stackToId = new HashMap<>();
    public HashMap<Long, String> idToStack = new HashMap<>();
    public ArrayList<Long> stackSamples = new ArrayList<>();
    public int[][] samples = new int[1024][];

    private static void appendMethodName(StringBuilder sb, StackTraceElement ste) {
        String classname = ste.getClassName();
        if (classname != null && classname.length() > 0) {
            sb.append(classname);
            sb.append(".");
        }
        sb.append(ste.getMethodName());
    }

    public void addStack(long traceId, StackTrace trace, JfrConverter converter) {
        int len = trace.methods.length;
        if (len == 0) return;

        long[] methods = trace.methods;
        byte[] types = trace.types;
        int[] locations = trace.locations;

        StackTraceElement ste = converter.getStackTraceElement(methods[len-1], types[len-1], locations[len-1]);
        String classname = ste.getClassName();
        StringBuilder sb = new StringBuilder();
        appendMethodName(sb, ste);
        for (int i = len-2; i >= 0; i--) {
            ste = converter.getStackTraceElement(methods[i], types[i], locations[i]);
            sb.append(";");
            appendMethodName(sb, ste);
        }
        // stacktraceNames.put(stackStr);
        String s = sb.toString();
        Long id = stackToId.computeIfAbsent(s, k -> Long.valueOf(stackToId.size()));
        traceIdToStack.putIfAbsent(traceId, s);
        idToStack.putIfAbsent(id, s);
    }

    public void addEvent(long traceId, long delta, long durationNanos) {
        int bucket = (int)(delta / BLOCK_DURATION_MS);
        String s = traceIdToStack.get(traceId);
        int index = stackToId.get(s).intValue();
        int len;
        // System.out.println("hm: %d = %d".formatted(index, bucket));
        while (index >= (len = samples.length)) {
            int[][] newSamples = new int[len * 3 / 2][];
            System.arraycopy(samples, 0, newSamples, 0, len);
            samples = newSamples;
        }
        int[] samplesList = samples[index];
        if (samplesList == null) {
            samplesList = new int[Math.max(bucket, 15) + 1];
            samples[index] = samplesList;
        }
        while (bucket >= (len = samplesList.length)) {
            int newLen = Math.min(len * 3 / 2, (int)(durationNanos / 1_000_000 / BLOCK_DURATION_MS));
            int[] newSamplesList = new int[newLen];
            System.arraycopy(samplesList, 0, newSamplesList, 0, len);
            samplesList = newSamplesList;
            samples[index] = newSamplesList;
        }
        samplesList[bucket]++;
    }
}
