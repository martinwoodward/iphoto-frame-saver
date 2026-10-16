package io.github.martinwoodward.iphotoframesaver.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Every photo once per cycle; the boundary also avoids an immediate repeat. */
public final class ShuffleDeck {
    private final List<String> ids;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Random random;
    private String last;

    public ShuffleDeck(List<String> ids, Random random, String last) {
        this.ids = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
        this.random = random;
        this.last = last;
    }

    private void fill(int count) {
        while (!ids.isEmpty() && queue.size() < count) {
            List<String> cycle = new ArrayList<>(ids);
            Collections.shuffle(cycle, random);
            String previous = queue.isEmpty() ? last : queue.peekLast();
            if (cycle.size() > 1 && cycle.get(0).equals(previous)) {
                Collections.swap(cycle, 0, 1 + random.nextInt(cycle.size() - 1));
            }
            queue.addAll(cycle);
        }
    }

    public synchronized String next() {
        fill(1);
        if (queue.isEmpty()) return null;
        last = queue.removeFirst();
        return last;
    }

    public synchronized List<String> peek(int count) {
        fill(count);
        List<String> result = new ArrayList<>();
        for (String id : queue) {
            if (result.size() == count) break;
            result.add(id);
        }
        return result;
    }
}
