package io.github.martinwoodward.iphotoframesaver.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Every photo once per cycle; the boundary also avoids an immediate repeat. */
public final class ShuffleDeck {
    private final List<String> ids;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Random random;
    private String last;
    private int remaining;

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
            if (remaining == 0) remaining = cycle.size();
        }
    }

    public synchronized String next() {
        fill(1);
        if (queue.isEmpty()) return null;
        String id = queue.peekFirst();
        consume(id);
        return id;
    }

    public synchronized String choose(List<String> candidates) {
        fill(1);
        Set<String> available = new HashSet<>(candidates);
        String fallback = null;
        int count = 0;
        for (String id : queue) {
            if (count++ == remaining) break;
            if (!available.contains(id)) continue;
            if (!id.equals(last)) return id;
            fallback = id;
        }
        return fallback;
    }

    public synchronized boolean consume(String id) {
        fill(1);
        if (!currentCycle().contains(id)) return false;
        if (!id.equals(queue.peekFirst())) discardFuture();
        queue.removeFirstOccurrence(id);
        last = id;
        remaining--;
        if (remaining == 0 && !queue.isEmpty()) remaining = ids.size();
        return true;
    }

    public synchronized void defer(String id) {
        fill(1);
        if (!currentCycle().contains(id)) return;
        discardFuture();
        queue.removeFirstOccurrence(id);
        queue.addLast(id);
    }

    public synchronized void update(List<String> members) {
        List<String> next = new ArrayList<>(new LinkedHashSet<>(members));
        if (new HashSet<>(next).equals(new HashSet<>(ids))) return;
        fill(1);
        List<String> unseen = currentCycle();
        unseen.retainAll(next);
        for (String id : next) if (!ids.contains(id)) unseen.add(random.nextInt(unseen.size() + 1), id);
        ids.clear();
        ids.addAll(next);
        queue.clear();
        queue.addAll(unseen);
        remaining = unseen.size();
    }

    private List<String> currentCycle() {
        List<String> result = new ArrayList<>();
        for (String id : queue) {
            if (result.size() == remaining) break;
            result.add(id);
        }
        return result;
    }

    private void discardFuture() {
        List<String> cycle = currentCycle();
        queue.clear();
        queue.addAll(cycle);
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
