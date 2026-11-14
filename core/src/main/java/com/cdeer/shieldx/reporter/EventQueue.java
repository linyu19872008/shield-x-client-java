package com.cdeer.shieldx.reporter;

import com.cdeer.shieldx.model.Event;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded FIFO with drop-oldest backpressure. */
final class EventQueue {

  private final int capacity;
  private final ArrayDeque<Event> items;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private boolean closed;

  EventQueue(int capacity) {
    this.capacity = capacity;
    this.items = new ArrayDeque<>(Math.min(capacity, 1024));
  }

  int offer(Event event) {
    lock.lock();
    try {
      int dropped = 0;
      if (items.size() >= capacity) {
        items.removeFirst();
        dropped = 1;
      }
      items.addLast(event);
      notEmpty.signal();
      return dropped;
    } finally {
      lock.unlock();
    }
  }

  List<Event> takeBatch(int max, Duration timeout) throws InterruptedException {
    lock.lock();
    try {
      long nanos = timeout.toNanos();
      while (items.isEmpty() && !closed && nanos > 0) {
        nanos = notEmpty.awaitNanos(nanos);
      }
      if (items.isEmpty()) {
        return List.of();
      }
      int count = Math.min(max, items.size());
      List<Event> batch = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        batch.add(items.removeFirst());
      }
      return batch;
    } finally {
      lock.unlock();
    }
  }

  int requeueFront(List<Event> events) {
    if (events.isEmpty()) {
      return 0;
    }
    lock.lock();
    try {
      for (int i = events.size() - 1; i >= 0; i--) {
        items.addFirst(events.get(i));
      }
      int dropped = 0;
      while (items.size() > capacity) {
        items.removeLast();
        dropped++;
      }
      notEmpty.signal();
      return dropped;
    } finally {
      lock.unlock();
    }
  }

  List<Event> drainAll() {
    lock.lock();
    try {
      List<Event> all = new ArrayList<>(items);
      items.clear();
      return all;
    } finally {
      lock.unlock();
    }
  }

  int size() {
    lock.lock();
    try {
      return items.size();
    } finally {
      lock.unlock();
    }
  }

  void close() {
    lock.lock();
    try {
      closed = true;
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }

  void wake() {
    lock.lock();
    try {
      notEmpty.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
