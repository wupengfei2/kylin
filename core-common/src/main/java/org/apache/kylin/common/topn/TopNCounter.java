/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.common.topn;

import com.google.common.collect.Lists;
import org.apache.kylin.common.util.Pair;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modified from the StreamSummary.java in https://github.com/addthis/stream-lib
 * 
 * Based on the <i>Space-Saving</i> algorithm and the <i>Stream-Summary</i>
 * data structure as described in:
 * <i>Efficient Computation of Frequent and Top-k Elements in Data Streams</i>
 * by Metwally, Agrawal, and Abbadi
 *
 * @param <T> type of data in the stream to be summarized
 */
public class TopNCounter<T> implements ITopK<T> {
    
    public static final int EXTRA_SPACE_RATE = 50;

    protected class Bucket {

        protected DoublyLinkedList<Counter<T>> counterList;

        private double count;

        public Bucket(double count) {
            this.count = count;
            this.counterList = new DoublyLinkedList<Counter<T>>();
        }

        public int size() {
            return counterList.size();
        }
    }

    protected int capacity;
    private HashMap<T, ListNode2<Counter<T>>> counterMap;
    protected DoublyLinkedList<Bucket> bucketList;

    /**
     * @param capacity maximum size (larger capacities improve accuracy)
     */
    public TopNCounter(int capacity) {
        this.capacity = capacity;
        counterMap = new HashMap<T, ListNode2<Counter<T>>>();
        bucketList = new DoublyLinkedList<Bucket>();
    }

    public int getCapacity() {
        return capacity;
    }

    /**
     * Algorithm: <i>Space-Saving</i>
     *
     * @param item stream element (<i>e</i>)
     * @return false if item was already in the stream summary, true otherwise
     */
    @Override
    public boolean offer(T item) {
        return offer(item, 1.0);
    }

    /**
     * Algorithm: <i>Space-Saving</i>
     *
     * @param item stream element (<i>e</i>)
     * @return false if item was already in the stream summary, true otherwise
     */
    @Override
    public boolean offer(T item, double incrementCount) {
        return offerReturnAll(item, incrementCount).getFirst();
    }

    /**
     * @param item stream element (<i>e</i>)
     * @return item dropped from summary if an item was dropped, null otherwise
     */
    public T offerReturnDropped(T item, double incrementCount) {
        return offerReturnAll(item, incrementCount).getSecond();
    }

    /**
     * @param item stream element (<i>e</i>)
     * @return Pair<isNewItem, itemDropped> where isNewItem is the return value of offer() and itemDropped is null if no item was dropped
     */
    public Pair<Boolean, T> offerReturnAll(T item, double incrementCount) {
        ListNode2<Counter<T>> counterNode = counterMap.get(item);
        boolean isNewItem = (counterNode == null);
        T droppedItem = null;
        if (isNewItem) {

            if (size() < capacity) {
                counterNode = bucketList.enqueue(new Bucket(0)).getValue().counterList.add(new Counter<T>(bucketList.tail(), item));
            } else {
                Bucket min = bucketList.first();
                counterNode = min.counterList.tail();
                Counter<T> counter = counterNode.getValue();
                droppedItem = counter.item;
                counterMap.remove(droppedItem);
                counter.item = item;
                counter.error = min.count;
            }
            counterMap.put(item, counterNode);
        }

        incrementCounter(counterNode, incrementCount);

        return new Pair<Boolean, T>(isNewItem, droppedItem);
    }

    protected void incrementCounter(ListNode2<Counter<T>> counterNode, double incrementCount) {
        Counter<T> counter = counterNode.getValue(); // count_i
        ListNode2<Bucket> oldNode = counter.bucketNode;
        Bucket bucket = oldNode.getValue(); // Let Bucket_i be the bucket of count_i
        bucket.counterList.remove(counterNode); // Detach count_i from Bucket_i's child-list
        counter.count = counter.count + incrementCount;

        // Finding the right bucket for count_i
        // Because we allow a single call to increment count more than once, this may not be the adjacent bucket. 
        ListNode2<Bucket> bucketNodePrev = oldNode;
        ListNode2<Bucket> bucketNodeNext = bucketNodePrev.getNext();
        while (bucketNodeNext != null) {
            Bucket bucketNext = bucketNodeNext.getValue(); // Let Bucket_i^+ be Bucket_i's neighbor of larger value
            if (counter.count == bucketNext.count) {
                bucketNext.counterList.add(counterNode); // Attach count_i to Bucket_i^+'s child-list
                break;
            } else if (counter.count > bucketNext.count) {
                bucketNodePrev = bucketNodeNext;
                bucketNodeNext = bucketNodePrev.getNext(); // Continue hunting for an appropriate bucket
            } else {
                // A new bucket has to be created
                bucketNodeNext = null;
            }
        }

        if (bucketNodeNext == null) {
            Bucket bucketNext = new Bucket(counter.count);
            bucketNext.counterList.add(counterNode);
            bucketNodeNext = bucketList.addAfter(bucketNodePrev, bucketNext);
        }
        counter.bucketNode = bucketNodeNext;

        //Cleaning up
        if (bucket.counterList.isEmpty()) // If Bucket_i's child-list is empty
        {
            bucketList.remove(oldNode); // Detach Bucket_i from the Stream-Summary
        }
    }

    @Override
    public List<T> peek(int k) {
        List<T> topK = new ArrayList<T>(k);

        for (ListNode2<Bucket> bNode = bucketList.head(); bNode != null; bNode = bNode.getPrev()) {
            Bucket b = bNode.getValue();
            for (Counter<T> c : b.counterList) {
                if (topK.size() == k) {
                    return topK;
                }
                topK.add(c.item);
            }
        }

        return topK;
    }

    public List<Counter<T>> topK(int k) {
        List<Counter<T>> topK = new ArrayList<Counter<T>>(k);

        for (ListNode2<Bucket> bNode = bucketList.head(); bNode != null; bNode = bNode.getPrev()) {
            Bucket b = bNode.getValue();
            for (Counter<T> c : b.counterList) {
                if (topK.size() == k) {
                    return topK;
                }
                topK.add(c);
            }
        }

        return topK;
    }

    /**
     * @return number of items stored
     */
    public int size() {
        return counterMap.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (ListNode2<Bucket> bNode = bucketList.head(); bNode != null; bNode = bNode.getPrev()) {
            Bucket b = bNode.getValue();
            sb.append('{');
            sb.append(b.count);
            sb.append(":[");
            for (Counter<T> c : b.counterList) {
                sb.append('{');
                sb.append(c.item);
                sb.append(':');
                sb.append(c.error);
                sb.append("},");
            }
            if (b.counterList.size() > 0) {
                sb.deleteCharAt(sb.length() - 1);
            }
            sb.append("]},");
        }
        if (bucketList.size() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        sb.append(']');
        return sb.toString();
    }

    public void fromExternal(int size, double[] counters, List<T> items) {
        this.bucketList = new DoublyLinkedList<Bucket>();

        this.counterMap = new HashMap<T, ListNode2<Counter<T>>>(size);

        Bucket currentBucket = null;
        ListNode2<Bucket> currentBucketNode = null;
        for (int i = 0; i < size; i++) {
            Counter<T> c = new Counter<T>();
            c.count = counters[i];
            c.item = items.get(i);
            if (currentBucket == null || c.count != currentBucket.count) {
                currentBucket = new Bucket(c.count);
                currentBucketNode = bucketList.add(currentBucket);
            }
            c.bucketNode = currentBucketNode;
            counterMap.put(c.item, currentBucket.counterList.add(c));
        }
    }

    /**
     * For de-serialization
     */
    public TopNCounter() {
    }

    /**
     * Merge another counter into this counter; Note, the other counter will be changed in this method; please make a copy and passed in here;
     * @param another
     * @return
     */
    public TopNCounter<T> merge(TopNCounter<T> another) {
        double m1 = 0.0, m2 = 0.0;
        if (this.size() >= this.capacity) {
            m1 = this.bucketList.tail().getValue().count;
        }

        if (another.size() >= another.capacity) {
            m2 = another.bucketList.tail().getValue().count;
        }

        for (Map.Entry<T, ListNode2<Counter<T>>> entry : this.counterMap.entrySet()) {
            T item = entry.getKey();
            ListNode2<Counter<T>> existing = another.counterMap.get(item);
            if (existing != null) {
                this.offer(item, another.counterMap.get(item).getValue().count);
                this.counterMap.get(item).getValue().error = entry.getValue().getValue().error + another.counterMap.get(item).getValue().error;

                another.counterMap.remove(item);
            } else {
                this.offer(item, m2);
                this.counterMap.get(item).getValue().error = entry.getValue().getValue().error + m2;
            }
        }

        for (Map.Entry<T, ListNode2<Counter<T>>> entry : another.counterMap.entrySet()) {
            T item = entry.getKey();
            double counter = entry.getValue().getValue().count;
            double error = entry.getValue().getValue().error;
            this.offer(item, counter + m1);
            this.counterMap.get(item).getValue().error = error + m1;
        }

        return this;
    }

    /**
     * Retain the capacity to the given number; The extra counters will be cut off
     * @param newCapacity
     */
    public void retain(int newCapacity) {
        assert newCapacity > 0;
        this.capacity = newCapacity;
        if (newCapacity < this.size()) {
            ListNode2<Bucket> tail = bucketList.tail;
            while (tail != null && this.size() > newCapacity) {
                Bucket bucket = tail.getValue();

                for (Counter<T> counter : bucket.counterList) {
                    this.counterMap.remove(counter.getItem());
                }
                tail = tail.getNext();
            }

            tail.next = null;
        }

    }

    /**
     * Get the counter values in ascending order
     * @return
     */
    public double[] getCounters() {
        double[] counters = new double[size()];
        int index = 0;

        for (ListNode2<Bucket> bNode = bucketList.tail(); bNode != null; bNode = bNode.getNext()) {
            Bucket b = bNode.getValue();
            for (Counter<T> c : b.counterList) {
                counters[index] = c.count;
                index ++;
            }
        }

        return counters;
    }

    /**
     * Get the item list order by counter values in ascending order
     * @return
     */
    public List<T> getItems() {
        List<T> items = Lists.newArrayList();
        for (ListNode2<Bucket> bNode = bucketList.tail(); bNode != null; bNode = bNode.getNext()) {
            Bucket b = bNode.getValue();
            for (Counter<T> c : b.counterList) {
                items.add(c.item);
            }
        }

        return items;

    }
}
