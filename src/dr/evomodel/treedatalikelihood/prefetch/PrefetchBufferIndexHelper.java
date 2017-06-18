/*
 * BufferIndexHelper.java
 *
 * Copyright (c) 2002-2016 Alexei Drummond, Andrew Rambaut and Marc Suchard
 *
 * This file is part of BEAST.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 *  BEAST is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package dr.evomodel.treedatalikelihood.prefetch;

import java.io.Serializable;
import java.util.*;

/**
 * BufferIndexHelper - helper for keeping track of buffers across multiple prefetch operations. This maps
 * node numbers to buffer indices for a set of N different trees. 
 *
 * @author Andrew Rambaut
 * @author Marc Suchard
 * @version $Id$
 */
public class PrefetchBufferIndexHelper implements Serializable {

    private static final boolean PREFETCH_DEBUG = false;

    /**
     * @param maxIndexValue the number of possible input values for the index
     * @param minIndexValue the minimum index value to have the mirrored buffers
     */
    public PrefetchBufferIndexHelper(String name, int prefetchCount, int maxIndexValue, int minIndexValue, int bufferSetNumber) {
        this.minIndexValue = minIndexValue;

        this.prefetchCount = prefetchCount;
        doubleBufferCount = maxIndexValue - minIndexValue;
        indexOffsets = new int[prefetchCount][doubleBufferCount];
        storedIndexOffsets = new int[prefetchCount][doubleBufferCount];

        // set up all the indices to the first doubleBufferCount of buffers
        for (int i = 0; i < prefetchCount; i++) {
            for (int j = 0; j < doubleBufferCount; j++) {
                indexOffsets[i][j] = j + minIndexValue;
            }
        }

        // then push all the remaining indices into the stack
        // need twice as many as there are being used at any one time.
        int k = doubleBufferCount + minIndexValue;
        for (int i = 0; i < prefetchCount; i++) {
            for (int j = 0; j < doubleBufferCount; j++) {
                availableIndices.push(k);
                k += 1;
            }
        }

        this.constantOffset = bufferSetNumber * getBufferCount();

        this.name = name;

        if (PREFETCH_DEBUG) System.err.println(name + " BIH initial avail = " + availableIndices.size());
    }

    public int getBufferCount() {
        return (prefetchCount * doubleBufferCount) + doubleBufferCount + minIndexValue;
    }

    public void flipOffset(int prefetch, int i) {

        assert(i >= minIndexValue) : "shouldn't be trying to flip the first 'static' indices";
        assert(availableIndices.size() > 0) : name + " availableIndices is empty (should not be)";

        // pop a new available buffer index
        indexOffsets[prefetch][i - minIndexValue] = availableIndices.pop();

        if (PREFETCH_DEBUG) System.err.println(name + " BIH flipOffset prefetch ="  + prefetch + " index = " + i +
                " new index = " + indexOffsets[prefetch][i - minIndexValue] + " (avail = " + availableIndices.size() + ")");
    }

    public int getBufferIndex(int prefetch, int i) {
        if (i < minIndexValue) {
            return i;
        }
        return indexOffsets[prefetch][i - minIndexValue] + constantOffset;
    }

    public void storeState() {
        if (PREFETCH_DEBUG) System.err.println(name + " BIH storeState");

        for (int i = 0; i < prefetchCount; i++) {
            System.arraycopy(indexOffsets[i], 0, storedIndexOffsets[i], 0, doubleBufferCount);
        }
        storedAvailableIndices.clear();
        storedAvailableIndices.addAll(availableIndices);
    }

    public void restoreState() {
        if (PREFETCH_DEBUG) System.err.println(name + " BIH restoreState");

        for (int i = 0; i < prefetchCount; i++) {
            int[] tmp = storedIndexOffsets[i];
            storedIndexOffsets[i] = indexOffsets[i];
            indexOffsets[i] = tmp;
        }
        Stack<Integer> tmp2 = availableIndices;
        availableIndices = storedAvailableIndices;
        storedAvailableIndices = tmp2;
    }

    public void acceptPrefetch(int prefetch) {


        Set<Integer> used = new HashSet<Integer>();
        for (Integer i : indexOffsets[prefetch]) {
            used.add(i);
        }

        availableIndices.clear();
        
        int k =  minIndexValue;
        for (int i = 0; i < prefetchCount + 1; i++) {
            for (int j = 0; j < doubleBufferCount; j++) {
                if (!used.contains(k)) {
                    if (PREFETCH_DEBUG) System.err.println(name + " BIH prefetch: " + prefetch + " pushing: " + k);
                    availableIndices.push(k);
                }
                k += 1;
            }
        }

        for (int i = 0; i < prefetchCount; i++) {
            if (i != prefetch) {
                System.arraycopy(indexOffsets[prefetch], 0, indexOffsets[i], 0, doubleBufferCount);
            }
        }

        if (PREFETCH_DEBUG) System.err.println(name + " BIH acceptPrefetch " + prefetch + " (avail: " + availableIndices.size() + ")");
    }

    private final int prefetchCount;
    private final int minIndexValue;
    private final int doubleBufferCount;
    private final int constantOffset;

    private int[][] indexOffsets;
    private int[][] storedIndexOffsets;

    private Stack<Integer> availableIndices = new Stack<Integer>();
    private Stack<Integer> storedAvailableIndices = new Stack<Integer>();

    String name;

}//END: class
