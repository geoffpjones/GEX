package clob.engine;

/**
 * One price on one side of a book: a FIFO of orders with displayed quantity, and a FIFO of fully
 * hidden orders. Intrusive doubly linked lists give O(1) add and remove.
 */
final class PriceLevel {
    long price;
    Order visHead;
    Order visTail;
    Order hidHead;
    Order hidTail;
    /** Sum of displayed slices. */
    long visibleQty;
    /** Hidden orders' leaves plus iceberg reserves. */
    long hiddenQty;
    /** Displayed quantity belonging to peg orders (excluded from peg and mid reference prices). */
    long pegVisibleQty;
    int orderCount;

    void reset(long price) {
        this.price = price;
        visHead = visTail = hidHead = hidTail = null;
        visibleQty = hiddenQty = pegVisibleQty = 0;
        orderCount = 0;
    }

    boolean isEmpty() {
        return orderCount == 0;
    }

    boolean hasNonPegVisible() {
        return visibleQty > pegVisibleQty;
    }

    void addVisible(Order o) {
        o.level = this;
        o.inVisibleQueue = true;
        o.prev = visTail;
        o.next = null;
        if (visTail == null) {
            visHead = o;
        } else {
            visTail.next = o;
        }
        visTail = o;
        visibleQty += o.displayQty;
        if (o.type == clob.refdata.OrderType.PEGGED) {
            pegVisibleQty += o.displayQty;
        }
        hiddenQty += o.leaves - o.displayQty;
        orderCount++;
    }

    void addHidden(Order o) {
        o.level = this;
        o.inVisibleQueue = false;
        o.prev = hidTail;
        o.next = null;
        if (hidTail == null) {
            hidHead = o;
        } else {
            hidTail.next = o;
        }
        hidTail = o;
        hiddenQty += o.leaves;
        orderCount++;
    }

    void remove(Order o) {
        if (o.inVisibleQueue) {
            if (o.prev == null) {
                visHead = o.next;
            } else {
                o.prev.next = o.next;
            }
            if (o.next == null) {
                visTail = o.prev;
            } else {
                o.next.prev = o.prev;
            }
            visibleQty -= o.displayQty;
            hiddenQty -= o.leaves - o.displayQty;
            if (o.type == clob.refdata.OrderType.PEGGED) {
                pegVisibleQty -= o.displayQty;
            }
        } else {
            if (o.prev == null) {
                hidHead = o.next;
            } else {
                o.prev.next = o.next;
            }
            if (o.next == null) {
                hidTail = o.prev;
            } else {
                o.next.prev = o.prev;
            }
            hiddenQty -= o.leaves;
        }
        o.prev = null;
        o.next = null;
        o.level = null;
        orderCount--;
    }

    /** Changes an order's quantities in place, keeping its queue position. */
    void adjust(Order o, long newLeaves, long newDisplay) {
        if (o.inVisibleQueue) {
            visibleQty += newDisplay - o.displayQty;
            if (o.type == clob.refdata.OrderType.PEGGED) {
                pegVisibleQty += newDisplay - o.displayQty;
            }
            hiddenQty += (newLeaves - newDisplay) - (o.leaves - o.displayQty);
        } else {
            hiddenQty += newLeaves - o.leaves;
        }
        o.leaves = newLeaves;
        o.displayQty = newDisplay;
    }
}
