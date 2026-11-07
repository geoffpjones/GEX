package clob.engine;

/** Intrusive FIFO of orders outside the price ladder (mid and fixing books). */
final class OrderQueue {
    Order head;
    Order tail;
    int count;

    boolean isEmpty() {
        return count == 0;
    }

    void add(Order o) {
        o.queue = this;
        o.prev = tail;
        o.next = null;
        if (tail == null) {
            head = o;
        } else {
            tail.next = o;
        }
        tail = o;
        count++;
    }

    void remove(Order o) {
        if (o.prev == null) {
            head = o.next;
        } else {
            o.prev.next = o.next;
        }
        if (o.next == null) {
            tail = o.prev;
        } else {
            o.next.prev = o.prev;
        }
        o.prev = null;
        o.next = null;
        o.queue = null;
        count--;
    }
}
