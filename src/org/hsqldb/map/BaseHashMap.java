/* Copyright (c) 2001-2019, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb.map;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.NoSuchElementException;

import org.hsqldb.lib.ArrayCounter;
import org.hsqldb.lib.Iterator;
import org.hsqldb.lib.ObjectComparator;

/**
 * Base class for hash tables or sets. The exact type of the structure is
 * defined by the constructor. Each instance has at least a keyTable array
 * and a HashIndex instance for looking up the keys into this table. Instances
 * that are maps also have a valueTable the same size as the keyTable.
 *
 * Special getOrAddXXX() methods are used for object maps in some subclasses.
 *
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @version 2.5.1
 * @since 1.7.2
 */
public class BaseHashMap {

/*

    data store:
    keys: {array of primitive | array of object}
    values: {none | array of primitive | array of object} same size as keys
    objects support : hashCode(), equals()

    implemented types of keyTable:
    {objectKeyTable: variable size Object[] array for keys |
    intKeyTable: variable size int[] for keys |
    longKeyTable: variable size long[] for keys }

    implemented types of valueTable:
    {objectValueTable: variable size Object[] array for values |
    intValueTable: variable size int[] for values |
    longValueTable: variable size long[] for values}

    valueTable does not exist for sets or for object pools

    hash index:
    hashTable: fixed size int[] array for hash lookup into keyTable
    linkTable: pointer to the next key ; size equal or larger than hashTable
    but equal to the valueTable

    access count table:
    {none |
    variable size int[] array for access count} same size as xxxKeyTable
*/

    //
    boolean           isIntKey;
    boolean           isLongKey;
    boolean           isObjectKey;
    boolean           isNoValue;
    boolean           isIntValue;
    boolean           isLongValue;
    boolean           isObjectValue;
    protected boolean isTwoObjectValue;
    protected boolean isList;
    protected boolean isAccessCount;
    protected boolean isLastAccessCount;

    //
    private ValuesIterator valuesIterator;

    //
    protected HashIndex hashIndex;

    //
    protected int[]    intKeyTable;
    protected Object[] objectKeyTable;
    protected long[]   longKeyTable;

    //
    protected int[]    intValueTable;
    protected Object[] objectValueTable;
    protected long[]   longValueTable;

    //
    protected int           accessMin;
    protected AtomicInteger accessCount;
    protected int[]         accessTable;
    protected boolean[]     multiValueTable;
    protected Object[]      objectValueTable2;

    //
    final float                loadFactor;
    final int                  initialCapacity;
    int                        threshold;
    protected int              maxCapacity;
    protected int              purgePolicy = NO_PURGE;
    protected boolean          minimizeOnEmpty;
    protected ObjectComparator comparator;

    //
    boolean hasZeroKey;
    int     zeroKeyIndex = -1;

    // keyOrValueTypes
    protected static final int noKeyOrValue     = 0;
    protected static final int intKeyOrValue    = 1;
    protected static final int longKeyOrValue   = 2;
    protected static final int objectKeyOrValue = 3;

    // purgePolicy
    protected static final int NO_PURGE   = 0;
    protected static final int PURGE_ALL  = 1;
    protected static final int PURGE_HALF = 2;

    //
    public static final int      ACCESS_MAX = Integer.MAX_VALUE - (1 << 20);
    public static final Object[] emptyObjectArray = new Object[]{};

    protected BaseHashMap(int initialCapacity, int keyType, int valueType,
                          boolean hasAccessCount)
                          throws IllegalArgumentException {

        if (initialCapacity <= 0) {
            throw new IllegalArgumentException();
        }

        if (initialCapacity < 3) {
            initialCapacity = 3;
        }

        this.loadFactor      = 1;    // can use any value if necessary
        this.initialCapacity = initialCapacity;
        threshold            = initialCapacity;

        int hashtablesize = (int) (initialCapacity * loadFactor);

        if (hashtablesize < 3) {
            hashtablesize = 3;
        }

        hashIndex = new HashIndex(hashtablesize, initialCapacity, true);

        int arraySize = threshold;

        if (keyType == BaseHashMap.intKeyOrValue) {
            isIntKey    = true;
            intKeyTable = new int[arraySize];
        } else if (keyType == BaseHashMap.objectKeyOrValue) {
            isObjectKey    = true;
            objectKeyTable = new Object[arraySize];
        } else {
            isLongKey    = true;
            longKeyTable = new long[arraySize];
        }

        if (valueType == BaseHashMap.intKeyOrValue) {
            isIntValue    = true;
            intValueTable = new int[arraySize];
        } else if (valueType == BaseHashMap.objectKeyOrValue) {
            isObjectValue    = true;
            objectValueTable = new Object[arraySize];
        } else if (valueType == BaseHashMap.longKeyOrValue) {
            isLongValue    = true;
            longValueTable = new long[arraySize];
        } else {
            isNoValue = true;
        }

        isLastAccessCount = hasAccessCount;

        if (hasAccessCount) {
            accessTable = new int[arraySize];
            accessCount = new AtomicInteger();
        }
    }

    protected int getLookup(Object key, int hash) {

        int    lookup = hashIndex.getLookup(hash);
        Object tempKey;

        for (; lookup >= 0; lookup = hashIndex.getNextLookup(lookup)) {
            tempKey = objectKeyTable[lookup];

            if (comparator == null) {
                if (key.equals(tempKey)) {
                    break;
                }
            } else {
                if (comparator.compare(key, tempKey) == 0) {
                    break;
                }
            }
        }

        return lookup;
    }

    protected int getLookup(int key) {

        int lookup = hashIndex.getLookup(key);
        int tempKey;

        for (; lookup >= 0; lookup = hashIndex.getNextLookup(lookup)) {
            tempKey = intKeyTable[lookup];

            if (key == tempKey) {
                break;
            }
        }

        return lookup;
    }

    protected int getLookup(long key) {

        int  lookup = hashIndex.getLookup((int) key);
        long tempKey;

        for (; lookup >= 0; lookup = hashIndex.getNextLookup(lookup)) {
            tempKey = longKeyTable[lookup];

            if (key == tempKey) {
                break;
            }
        }

        return lookup;
    }

    protected int getObjectLookup(long key) {

        int  lookup = hashIndex.getLookup((int) key);
        long tempKey;

        for (; lookup >= 0; lookup = hashIndex.getNextLookup(lookup)) {
            tempKey = comparator.longKey(objectKeyTable[lookup]);

            if (tempKey == key) {
                break;
            }
        }

        return lookup;
    }

    protected Iterator getValuesIterator(Object key, int hash) {

        int lookup = getLookup(key, hash);

        if (valuesIterator == null) {
            valuesIterator = new ValuesIterator();
        }

        valuesIterator.reset(key, lookup);

        return valuesIterator;
    }

    protected int valueCount(Object key, int hash) {

        int lookup = getLookup(key, hash);

        if (lookup == -1) {
            return 0;
        }

        int count = 1;

        while (true) {
            lookup = BaseHashMap.this.hashIndex.getNextLookup(lookup);

            if (lookup == -1) {
                break;
            }

            if (BaseHashMap.this.objectKeyTable[lookup].equals(key)) {
                count++;
            }
        }

        return count;
    }

    /**
     * generic method for adding or removing keys
     *
     * returns existing Object value if any (or Object key if this is a set)
     */
    protected Object addOrRemove(long longKey, long longValue,
                                 Object objectKey, Object objectValue,
                                 boolean remove) {

        int hash = (int) longKey;

        if (isObjectKey) {
            if (objectKey == null) {
                return null;
            }

            if (comparator == null) {
                hash = objectKey.hashCode();
            } else {
                hash = comparator.hashCode(objectKey);
            }
        }

        int    index       = hashIndex.getHashIndex(hash);
        int    lookup      = hashIndex.hashTable[index];
        int    lastLookup  = -1;
        Object returnValue = null;

        for (; lookup >= 0;
                lastLookup = lookup,
                lookup = hashIndex.getNextLookup(lookup)) {
            if (isObjectKey) {
                if (comparator == null) {
                    if (objectKeyTable[lookup].equals(objectKey)) {
                        break;
                    }
                } else {
                    if (comparator.compare(objectKeyTable[lookup], objectKey)
                            == 0) {
                        break;
                    }
                }
            } else if (isIntKey) {
                if (longKey == intKeyTable[lookup]) {
                    break;
                }
            } else if (isLongKey) {
                if (longKey == longKeyTable[lookup]) {
                    break;
                }
            }
        }

        if (lookup >= 0) {
            if (remove) {
                if (isObjectKey) {
                    objectKeyTable[lookup] = null;
                } else {
                    if (longKey == 0) {
                        hasZeroKey   = false;
                        zeroKeyIndex = -1;
                    }

                    if (isIntKey) {
                        intKeyTable[lookup] = 0;
                    } else {
                        longKeyTable[lookup] = 0;
                    }
                }

                if (isObjectValue) {
                    returnValue              = objectValueTable[lookup];
                    objectValueTable[lookup] = null;
                } else if (isIntValue) {
                    intValueTable[lookup] = 0;
                } else if (isLongValue) {
                    longValueTable[lookup] = 0;
                }

                hashIndex.unlinkNode(index, lastLookup, lookup);

                if (accessTable != null) {
                    accessTable[lookup] = 0;
                }

                if (minimizeOnEmpty && hashIndex.elementCount == 0) {
                    rehash(initialCapacity);
                }

                return returnValue;
            }

            if (isObjectKey) {
                returnValue = objectKeyTable[lookup];
            }

            if (isObjectValue) {
                returnValue              = objectValueTable[lookup];
                objectValueTable[lookup] = objectValue;
            } else if (isIntValue) {
                intValueTable[lookup] = (int) longValue;
            } else if (isLongValue) {
                longValueTable[lookup] = longValue;
            }

            if (isLastAccessCount) {
                accessTable[lookup] = accessCount.incrementAndGet();
            } else if (isAccessCount) {
                accessTable[lookup]++;
            }

            return returnValue;
        }

        // not found
        if (remove) {
            return null;
        }

        if (hashIndex.elementCount >= threshold) {
            if (reset()) {
                return addOrRemove(longKey, longValue, objectKey, objectValue,
                                   remove);
            } else {
                throw new NoSuchElementException("BaseHashMap");
            }
        }

        lookup = hashIndex.linkNode(index, lastLookup);

        // type dependent block
        if (isObjectKey) {
            objectKeyTable[lookup] = objectKey;
        } else if (isIntKey) {
            intKeyTable[lookup] = (int) longKey;

            if (longKey == 0) {
                hasZeroKey   = true;
                zeroKeyIndex = lookup;
            }
        } else if (isLongKey) {
            longKeyTable[lookup] = longKey;

            if (longKey == 0) {
                hasZeroKey   = true;
                zeroKeyIndex = lookup;
            }
        }

        if (isObjectValue) {
            objectValueTable[lookup] = objectValue;
        } else if (isIntValue) {
            intValueTable[lookup] = (int) longValue;
        } else if (isLongValue) {
            longValueTable[lookup] = longValue;
        }

        //
        if (isLastAccessCount) {
            accessTable[lookup] = accessCount.incrementAndGet();
        } else if (isAccessCount) {
            accessTable[lookup] = 1;
        }

        return returnValue;
    }

    /**
     * Generic method for adding or removing key / values in multi-value maps.
     */
    protected Object addOrRemoveMultiVal(long longKey, long longValue,
                                         Object objectKey, Object objectValue,
                                         boolean removeKey,
                                         boolean removeValue) {

        int hash = (int) longKey;

        if (isObjectKey) {
            if (objectKey == null) {
                return null;
            }

            if (comparator == null) {
                hash = objectKey.hashCode();
            } else {
                hash = comparator.hashCode(objectKey);
            }
        }

        int     index       = hashIndex.getHashIndex(hash);
        int     lookup      = hashIndex.hashTable[index];
        int     lastLookup  = -1;
        Object  returnValue = null;
        boolean multiValue  = false;

        for (; lookup >= 0;
                lastLookup = lookup,
                lookup = hashIndex.getNextLookup(lookup)) {
            if (isObjectKey) {
                if (comparator == null) {
                    if (objectKeyTable[lookup].equals(objectKey)) {}
                    else {
                        continue;
                    }
                } else {
                    if (comparator.compare(objectKeyTable[lookup], objectKey)
                            == 0) {}
                    else {
                        continue;
                    }
                }

                if (removeKey) {
                    while (true) {
                        objectKeyTable[lookup]   = null;
                        returnValue              = objectValueTable[lookup];
                        objectValueTable[lookup] = null;

                        hashIndex.unlinkNode(index, lastLookup, lookup);

                        multiValueTable[lookup] = false;
                        lookup                  = hashIndex.hashTable[index];

                        if (lookup < 0
                                || !objectKeyTable[lookup].equals(objectKey)) {
                            return returnValue;
                        }
                    }
                } else {
                    if (objectValueTable[lookup].equals(objectValue)) {
                        if (removeValue) {
                            objectKeyTable[lookup]   = null;
                            returnValue = objectValueTable[lookup];
                            objectValueTable[lookup] = null;

                            hashIndex.unlinkNode(index, lastLookup, lookup);

                            multiValueTable[lookup] = false;
                            lookup                  = lastLookup;

                            return returnValue;
                        } else {
                            return objectValueTable[lookup];
                        }
                    }
                }

                multiValue = true;
            } else if (isIntKey) {
                if (longKey == intKeyTable[lookup]) {
                    if (removeKey) {
                        while (true) {
                            if (longKey == 0) {
                                hasZeroKey   = false;
                                zeroKeyIndex = -1;
                            }

                            intKeyTable[lookup]   = 0;
                            intValueTable[lookup] = 0;

                            hashIndex.unlinkNode(index, lastLookup, lookup);

                            multiValueTable[lookup] = false;
                            lookup = hashIndex.hashTable[index];

                            if (lookup < 0 || longKey != intKeyTable[lookup]) {
                                return null;
                            }
                        }
                    } else {
                        if (intValueTable[lookup] == longValue) {
                            return null;
                        }
                    }

                    multiValue = true;
                }
            } else if (isLongKey) {
                if (longKey == longKeyTable[lookup]) {
                    if (removeKey) {
                        while (true) {
                            if (longKey == 0) {
                                hasZeroKey   = false;
                                zeroKeyIndex = -1;
                            }

                            longKeyTable[lookup]   = 0;
                            longValueTable[lookup] = 0;

                            hashIndex.unlinkNode(index, lastLookup, lookup);

                            multiValueTable[lookup] = false;
                            lookup = hashIndex.hashTable[index];

                            if (lookup < 0
                                    || longKey != longKeyTable[lookup]) {
                                return null;
                            }
                        }
                    } else {
                        if (intValueTable[lookup] == longValue) {
                            return null;
                        }
                    }

                    multiValue = true;
                }
            }
        }

        if (removeKey || removeValue) {
            return returnValue;
        }

        if (hashIndex.elementCount >= threshold) {
            if (reset()) {
                return addOrRemoveMultiVal(longKey, longValue, objectKey,
                                           objectValue, removeKey,
                                           removeValue);
            } else {
                throw new NoSuchElementException("BaseHashMap");
            }
        }

        lookup = hashIndex.linkNode(index, lastLookup);

        // type dependent block
        if (isObjectKey) {
            objectKeyTable[lookup] = objectKey;
        } else if (isIntKey) {
            intKeyTable[lookup] = (int) longKey;

            if (longKey == 0) {
                hasZeroKey   = true;
                zeroKeyIndex = lookup;
            }
        } else if (isLongKey) {
            longKeyTable[lookup] = longKey;

            if (longKey == 0) {
                hasZeroKey   = true;
                zeroKeyIndex = lookup;
            }
        }

        if (isObjectValue) {
            objectValueTable[lookup] = objectValue;
        } else if (isIntValue) {
            intValueTable[lookup] = (int) longValue;
        } else if (isLongValue) {
            longValueTable[lookup] = longValue;
        }

        if (multiValue) {
            multiValueTable[lookup] = true;
        }

        //
        if (isLastAccessCount) {
            accessTable[lookup] = accessCount.incrementAndGet();
        } else if (isAccessCount) {
            accessTable[lookup] = 1;
        }

        return returnValue;
    }

    /**
     * type-specific method for adding or removing keys in long or int->Object maps
     */
    protected Object addOrRemove(long longKey, Object objectValue,
                                 Object objectValueTwo, boolean remove) {

        int    hash        = (int) longKey;
        int    index       = hashIndex.getHashIndex(hash);
        int    lookup      = hashIndex.hashTable[index];
        int    lastLookup  = -1;
        Object returnValue = null;

        for (; lookup >= 0;
                lastLookup = lookup,
                lookup = hashIndex.getNextLookup(lookup)) {
            if (isIntKey) {
                if (longKey == intKeyTable[lookup]) {
                    break;
                }
            } else {
                if (longKey == longKeyTable[lookup]) {
                    break;
                }
            }
        }

        if (lookup >= 0) {
            if (remove) {
                if (longKey == 0) {
                    hasZeroKey   = false;
                    zeroKeyIndex = -1;
                }

                if (isIntKey) {
                    intKeyTable[lookup] = 0;
                } else {
                    longKeyTable[lookup] = 0;
                }

                returnValue              = objectValueTable[lookup];
                objectValueTable[lookup] = null;

                hashIndex.unlinkNode(index, lastLookup, lookup);

                if (isTwoObjectValue) {
                    objectKeyTable[lookup] = null;
                }

                if (accessTable != null) {
                    accessTable[lookup] = 0;
                }

                return returnValue;
            }

            if (isObjectValue) {
                returnValue              = objectValueTable[lookup];
                objectValueTable[lookup] = objectValue;
            }

            if (isTwoObjectValue) {
                objectKeyTable[lookup] = objectValueTwo;
            }

            if (isLastAccessCount) {
                accessTable[lookup] = accessCount.incrementAndGet();
            } else if (isAccessCount) {
                accessTable[lookup]++;
            }

            return returnValue;
        }

        // not found
        if (remove) {
            return returnValue;
        }

        if (hashIndex.elementCount >= threshold) {
            if (reset()) {
                return addOrRemove(longKey, objectValue, objectValueTwo,
                                   remove);
            } else {
                return null;
            }
        }

        lookup = hashIndex.linkNode(index, lastLookup);

        if (isIntKey) {
            intKeyTable[lookup] = (int) longKey;
        } else {
            longKeyTable[lookup] = longKey;
        }

        if (longKey == 0) {
            hasZeroKey   = true;
            zeroKeyIndex = lookup;
        }

        objectValueTable[lookup] = objectValue;

        if (isTwoObjectValue) {
            objectKeyTable[lookup] = objectValueTwo;
        }

        if (isLastAccessCount) {
            accessTable[lookup] = accessCount.incrementAndGet();
        } else if (isAccessCount) {
            accessTable[lookup] = 1;
        }

        return returnValue;
    }

    /**
     * type specific method for Object sets or Object->Object maps
     */
    protected Object removeObject(Object objectKey, boolean removeRow) {

        if (objectKey == null) {
            return null;
        }

        int    hash        = objectKey.hashCode();
        int    index       = hashIndex.getHashIndex(hash);
        int    lookup      = hashIndex.hashTable[index];
        int    lastLookup  = -1;
        Object returnValue = null;

        for (; lookup >= 0;
                lastLookup = lookup,
                lookup = hashIndex.getNextLookup(lookup)) {
            if (objectKeyTable[lookup].equals(objectKey)) {
                returnValue            = objectKeyTable[lookup];
                objectKeyTable[lookup] = null;

                if (accessTable != null) {
                    accessTable[lookup] = 0;
                }

                hashIndex.unlinkNode(index, lastLookup, lookup);

                if (isObjectValue) {
                    returnValue              = objectValueTable[lookup];
                    objectValueTable[lookup] = null;
                }

                if (removeRow) {
                    removeRow(lookup);
                }

                return returnValue;
            }
        }

        // not found
        return returnValue;
    }

    /**
     * For object sets using long key attribute of object for equality and
     * hash
     */
    protected Object addOrRemoveObject(Object object, long longKey,
                                       boolean remove) {

        int    hash        = (int) longKey;
        int    index       = hashIndex.getHashIndex(hash);
        int    lookup      = hashIndex.getLookup(hash);
        int    lastLookup  = -1;
        Object returnValue = null;

        for (; lookup >= 0;
                lastLookup = lookup,
                lookup = hashIndex.getNextLookup(lookup)) {
            if (comparator.longKey(objectKeyTable[lookup]) == longKey) {
                returnValue = objectKeyTable[lookup];

                break;
            }
        }

        if (lookup >= 0) {
            if (remove) {
                objectKeyTable[lookup] = null;

                hashIndex.unlinkNode(index, lastLookup, lookup);

                if (accessTable != null) {
                    accessTable[lookup] = 0;
                }

                if (minimizeOnEmpty && hashIndex.elementCount == 0) {
                    rehash(initialCapacity);
                }
            } else {
                objectKeyTable[lookup] = object;

                if (isLastAccessCount) {
                    accessTable[lookup] = accessCount.incrementAndGet();
                } else if (isAccessCount) {
                    accessTable[lookup]++;
                }
            }

            return returnValue;
        } else if (remove) {
            return null;
        }

        if (hashIndex.elementCount >= threshold) {
            if (reset()) {
                return addOrRemoveObject(object, longKey, remove);
            } else {
                throw new NoSuchElementException("BaseHashMap");
            }
        }

        lookup                 = hashIndex.linkNode(index, lastLookup);
        objectKeyTable[lookup] = object;

        if (isLastAccessCount) {
            accessTable[lookup] = accessCount.incrementAndGet();
        } else if (isAccessCount) {
            accessTable[lookup] = 1;
        }

        return returnValue;
    }

    protected boolean reset() {

        if (maxCapacity == 0 || maxCapacity > threshold) {
            rehash(hashIndex.linkTable.length * 2);

            return true;
        }

        switch (purgePolicy) {

            case PURGE_ALL :
                clear();

                return true;

            case PURGE_HALF :
                clearToHalf();

                return true;

            case NO_PURGE :
            default :
                return false;
        }
    }

    /**
     * rehash uses existing key and element arrays. key / value pairs are
     * put back into the arrays from the top, removing any gaps. any redundant
     * key / value pairs duplicated at the end of the array are then cleared.
     *
     * newCapacity must be larger or equal to existing number of elements.
     */
    protected void rehash(int newCapacity) {

        int     limitLookup     = hashIndex.newNodePointer;
        boolean oldZeroKey      = hasZeroKey;
        int     oldZeroKeyIndex = zeroKeyIndex;

        if (newCapacity < hashIndex.elementCount) {
            return;
        }

        hashIndex.reset((int) (newCapacity * loadFactor), newCapacity);

        if (multiValueTable != null) {
            int counter = multiValueTable.length;

            while (--counter >= 0) {
                multiValueTable[counter] = false;
            }
        }

        hasZeroKey   = false;
        zeroKeyIndex = -1;
        threshold    = newCapacity;

        for (int lookup = -1;
                (lookup = nextLookup(lookup, limitLookup, oldZeroKey, oldZeroKeyIndex))
                < limitLookup; ) {
            long   longKey     = 0;
            long   longValue   = 0;
            Object objectKey   = null;
            Object objectValue = null;

            if (isObjectKey) {
                objectKey = objectKeyTable[lookup];
            } else if (isIntKey) {
                longKey = intKeyTable[lookup];
            } else {
                longKey = longKeyTable[lookup];
            }

            if (isObjectValue) {
                objectValue = objectValueTable[lookup];
            } else if (isIntValue) {
                longValue = intValueTable[lookup];
            } else if (isLongValue) {
                longValue = longValueTable[lookup];
            }

            if (multiValueTable == null) {
                addOrRemove(longKey, longValue, objectKey, objectValue, false);
            } else {
                addOrRemoveMultiVal(longKey, longValue, objectKey,
                                    objectValue, false, false);
            }

            if (accessTable != null) {
                accessTable[hashIndex.elementCount - 1] = accessTable[lookup];
            }
        }

        resizeElementArrays(hashIndex.newNodePointer, newCapacity);
    }

    /**
     * resize the arrays containing the key / value data
     */
    private void resizeElementArrays(int dataLength, int newLength) {

        Object temp;
        int    usedLength = newLength > dataLength ? dataLength
                                                   : newLength;

        if (isIntKey) {
            temp        = intKeyTable;
            intKeyTable = new int[newLength];

            System.arraycopy(temp, 0, intKeyTable, 0, usedLength);
        }

        if (isIntValue) {
            temp          = intValueTable;
            intValueTable = new int[newLength];

            System.arraycopy(temp, 0, intValueTable, 0, usedLength);
        }

        if (isLongKey) {
            temp         = longKeyTable;
            longKeyTable = new long[newLength];

            System.arraycopy(temp, 0, longKeyTable, 0, usedLength);
        }

        if (isLongValue) {
            temp           = longValueTable;
            longValueTable = new long[newLength];

            System.arraycopy(temp, 0, longValueTable, 0, usedLength);
        }

        if (objectKeyTable != null) {
            temp           = objectKeyTable;
            objectKeyTable = new Object[newLength];

            System.arraycopy(temp, 0, objectKeyTable, 0, usedLength);
        }

        if (isObjectValue) {
            temp             = objectValueTable;
            objectValueTable = new Object[newLength];

            System.arraycopy(temp, 0, objectValueTable, 0, usedLength);
        }

        if (objectValueTable2 != null) {
            temp              = objectValueTable2;
            objectValueTable2 = new Object[newLength];

            System.arraycopy(temp, 0, objectValueTable2, 0, usedLength);
        }

        if (accessTable != null) {
            temp        = accessTable;
            accessTable = new int[newLength];

            System.arraycopy(temp, 0, accessTable, 0, usedLength);
        }

        if (multiValueTable != null) {
            temp            = multiValueTable;
            multiValueTable = new boolean[newLength];

            System.arraycopy(temp, 0, multiValueTable, 0, usedLength);
        }
    }

    /**
     * clear all the key / value data in a range.
     */
    private void clearElementArrays(final int from, final int to) {

        if (intKeyTable != null) {
            Arrays.fill(intKeyTable, from, to, 0);
        } else if (longKeyTable != null) {
            Arrays.fill(longKeyTable, from, to, 0);
        } else if (objectKeyTable != null) {
            Arrays.fill(objectKeyTable, from, to, null);
        }

        if (intValueTable != null) {
            Arrays.fill(intValueTable, from, to, 0);
        } else if (longValueTable != null) {
            Arrays.fill(longValueTable, from, to, 0);
        } else if (objectValueTable != null) {
            Arrays.fill(objectValueTable, from, to, null);
        }

        if (objectValueTable2 != null) {
            Arrays.fill(objectValueTable2, from, to, null);
        }

        if (accessTable != null) {
            Arrays.fill(accessTable, from, to, 0);
        }

        if (multiValueTable != null) {
            Arrays.fill(multiValueTable, from, to, false);
        }
    }

    /**
     * move the elements after a removed key / value pair to fill the gap
     */
    void removeFromElementArrays(int lookup) {

        // this is newNodePointer post-removal
        int lastPointer = hashIndex.newNodePointer;

        if (isIntKey) {
            Object array = intKeyTable;

            System.arraycopy(array, lookup + 1, array, lookup,
                             lastPointer - lookup);

            intKeyTable[lastPointer] = 0;
        }

        if (isLongKey) {
            Object array = longKeyTable;

            System.arraycopy(array, lookup + 1, array, lookup,
                             lastPointer - lookup);

            longKeyTable[lastPointer] = 0;
        }

        if (objectKeyTable != null) {
            Object array = objectKeyTable;

            System.arraycopy(array, lookup + 1, array, lookup,
                             lastPointer - lookup);

            objectKeyTable[lastPointer] = null;
        }

        if (isIntValue) {
            Object array = intValueTable;

            System.arraycopy(array, lookup + 1, array, lookup,
                             lastPointer - lookup);

            intValueTable[lastPointer] = 0;
        }

        if (isLongValue) {
            Object array = longValueTable;

            System.arraycopy(array, lookup + 1, array, lookup,
                             lastPointer - lookup);

            longValueTable[lastPointer] = 0;
        }

        if (isObjectValue) {
            Object array = objectValueTable;

            System.arraycopy(array, lookup + 1, array, lookup,
                             lastPointer - lookup);

            objectValueTable[lastPointer] = null;
        }
    }

    /**
     * find the next lookup in the key/value tables with an entry
     * allows the use of old limit and zero int key attributes
     */
    int nextLookup(int lookup, int limitLookup, boolean hasZeroKey,
                   int zeroKeyIndex) {

        for (++lookup; lookup < limitLookup; lookup++) {
            if (isObjectKey) {
                if (objectKeyTable[lookup] != null) {
                    return lookup;
                }
            } else if (isIntKey) {
                if (intKeyTable[lookup] != 0) {
                    return lookup;
                } else if (hasZeroKey && lookup == zeroKeyIndex) {
                    return lookup;
                }
            } else {
                if (longKeyTable[lookup] != 0) {
                    return lookup;
                } else if (hasZeroKey && lookup == zeroKeyIndex) {
                    return lookup;
                }
            }
        }

        return lookup;
    }

    /**
     * find the next lookup in the key/value tables with an entry
     * uses current limits and zero integer key state
     */
    protected int nextLookup(int lookup) {

        for (++lookup; lookup < hashIndex.newNodePointer; lookup++) {
            if (isObjectKey) {
                if (objectKeyTable[lookup] != null) {
                    return lookup;
                }
            } else if (isIntKey) {
                if (intKeyTable[lookup] != 0) {
                    return lookup;
                } else if (hasZeroKey && lookup == zeroKeyIndex) {
                    return lookup;
                }
            } else {
                if (longKeyTable[lookup] != 0) {
                    return lookup;
                } else if (hasZeroKey && lookup == zeroKeyIndex) {
                    return lookup;
                }
            }
        }

        return -1;
    }

    /**
     * row must already been freed of key / element
     */
    protected void removeRow(int lookup) {
        hashIndex.removeEmptyNode(lookup);
        removeFromElementArrays(lookup);
    }

    /**
     * Clear the map completely.
     */
    public void clear() {

        if (hashIndex.modified) {
            if (accessCount != null) {
                accessCount.set(0);
            }

            accessMin    = 0;
            hasZeroKey   = false;
            zeroKeyIndex = -1;

            clearElementArrays(0, hashIndex.linkTable.length);
            hashIndex.clear();

            if (minimizeOnEmpty) {
                rehash(initialCapacity);
            }
        }
    }

    /**
     * Return the max accessCount value for count elements with the lowest
     * access count. Always return at least accessMin + 1
     */
    protected int getAccessCountCeiling(int count, int margin) {
        return ArrayCounter.rank(accessTable, hashIndex.newNodePointer, count,
                                 accessMin, accessCount.get(), margin);
    }

    /**
     * This is called after all elements below count accessCount have been
     * removed
     */
    protected void setAccessCountFloor(int count) {
        accessMin = count;
    }

    /**
     * Clear approximately half elements from the map, starting with
     * those with low accessTable ranking.
     *
     * Only for value maps
     */
    private void clearToHalf() {

        int count  = threshold >> 1;
        int margin = threshold >> 8;

        if (margin < 64) {
            margin = 64;
        }

        int maxlookup  = hashIndex.newNodePointer;
        int accessBase = getAccessCountCeiling(count, margin);

        for (int lookup = 0; lookup < maxlookup; lookup++) {
            Object o = objectKeyTable[lookup];

            if (o != null && accessTable[lookup] < accessBase) {
                removeObject(o, false);
            }
        }

        accessMin = accessBase;

        if (hashIndex.elementCount > threshold - margin) {
            clear();
        }
    }

    protected void resetAccessCount() {

        int accessMax = accessCount.get();

        if (accessMax > 0 && accessMax < ACCESS_MAX) {
            return;
        }

        int limit = hashIndex.getNewNodePointer();

        accessMax = 0;
        accessMin = Integer.MAX_VALUE;

        for (int i = 0; i < limit; i++) {
            int access = accessTable[i];

            if (access == 0) {
                continue;
            }

            access         = (access >>> 2) + 1;
            accessTable[i] = access;

            if (access > accessMax) {
                accessMax = access;
            } else if (access < accessMin) {
                accessMin = access;
            }
        }

        if (accessMin > accessMax) {
            accessMin = accessMax;
        }

        accessCount.set(accessMax);
    }

    protected int capacity() {
        return hashIndex.linkTable.length;
    }

    public int size() {
        return hashIndex.elementCount;
    }

    public boolean isEmpty() {
        return hashIndex.elementCount == 0;
    }

    protected void setComparator(ObjectComparator comparator) {
        this.comparator = comparator;
    }

    protected boolean containsKey(Object key) {

        if (key == null) {
            return false;
        }

        if (hashIndex.elementCount == 0) {
            return false;
        }

        int lookup = getLookup(key, key.hashCode());

        return lookup == -1 ? false
                            : true;
    }

    protected boolean containsKey(int key) {

        if (hashIndex.elementCount == 0) {
            return false;
        }

        int lookup = getLookup(key);

        return lookup == -1 ? false
                            : true;
    }

    protected boolean containsKey(long key) {

        if (hashIndex.elementCount == 0) {
            return false;
        }

        int lookup = getLookup(key);

        return lookup == -1 ? false
                            : true;
    }

    protected boolean containsValue(Object value) {

        int lookup = 0;

        if (hashIndex.elementCount == 0) {
            return false;
        }

        if (value == null) {
            for (; lookup < hashIndex.newNodePointer; lookup++) {
                if (objectValueTable[lookup] == null) {
                    if (isObjectKey) {
                        if (objectKeyTable[lookup] != null) {
                            return true;
                        }
                    } else if (isIntKey) {
                        if (intKeyTable[lookup] != 0) {
                            return true;
                        } else if (hasZeroKey && lookup == zeroKeyIndex) {
                            return true;
                        }
                    } else {
                        if (longKeyTable[lookup] != 0) {
                            return true;
                        } else if (hasZeroKey && lookup == zeroKeyIndex) {
                            return true;
                        }
                    }
                }
            }
        } else {
            for (; lookup < hashIndex.newNodePointer; lookup++) {
                if (value.equals(objectValueTable[lookup])) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Currently only for object maps
     */
    protected class ValuesIterator implements Iterator {

        int    lookup = -1;
        Object key;

        private void reset(Object key, int lookup) {
            this.key    = key;
            this.lookup = lookup;
        }

        public boolean hasNext() {
            return lookup != -1;
        }

        public Object next() throws NoSuchElementException {

            if (lookup == -1) {
                return null;
            }

            Object value = BaseHashMap.this.objectValueTable[lookup];

            while (true) {
                lookup = BaseHashMap.this.hashIndex.getNextLookup(lookup);

                if (lookup == -1
                        || BaseHashMap.this.objectKeyTable[lookup].equals(
                            key)) {
                    break;
                }
            }

            return value;
        }

        public int nextInt() throws NoSuchElementException {
            throw new NoSuchElementException("Hash Iterator");
        }

        public long nextLong() throws NoSuchElementException {
            throw new NoSuchElementException("Hash Iterator");
        }

        public void remove() throws NoSuchElementException {
            throw new NoSuchElementException("Hash Iterator");
        }

        public void setValue(Object value) {
            throw new NoSuchElementException("Hash Iterator");
        }
    }

    protected class MultiValueKeyIterator implements Iterator {

        boolean keys;
        int     lookup = -1;
        int     counter;
        boolean removed;
        Object  oldKey;

        public MultiValueKeyIterator() {
            toNextLookup();
        }

        private void toNextLookup() {

            while (true) {
                lookup = nextLookup(lookup);

                if (lookup == -1 || !multiValueTable[lookup]) {
                    break;
                }
            }
        }

        public boolean hasNext() {
            return lookup != -1;
        }

        public Object next() throws NoSuchElementException {

            Object value = objectKeyTable[lookup];

            toNextLookup();

            oldKey = value;

            return value;
        }

        public int nextInt() throws NoSuchElementException {
            throw new NoSuchElementException("Hash Iterator");
        }

        public long nextLong() throws NoSuchElementException {
            throw new NoSuchElementException("Hash Iterator");
        }

        public void remove() throws NoSuchElementException {
            addOrRemoveMultiVal(0, 0, oldKey, null, true, false);
        }

        public void setValue(Object value) {
            throw new NoSuchElementException("Hash Iterator");
        }
    }

    /**
     * Iterator returns Object, int or long and is used both for keys and
     * values
     */
    protected class BaseHashIterator implements Iterator {

        boolean keys;
        int     lookup = -1;
        int     counter;
        boolean removed;

        /**
         * default is iterator for values
         */
        public BaseHashIterator() {}

        public BaseHashIterator(boolean keys) {
            this.keys = keys;
        }

        public void reset() {

            this.lookup  = -1;
            this.counter = 0;
            this.removed = false;
        }

        public boolean hasNext() {
            return counter < hashIndex.elementCount;
        }

        public Object next() throws NoSuchElementException {

            if ((keys && !isObjectKey) || (!keys && !isObjectValue)) {
                throw new NoSuchElementException("Hash Iterator");
            }

            removed = false;

            if (hasNext()) {
                counter++;

                lookup = nextLookup(lookup);

                if (keys) {
                    return objectKeyTable[lookup];
                } else {
                    return objectValueTable[lookup];
                }
            }

            throw new NoSuchElementException("Hash Iterator");
        }

        public int nextInt() throws NoSuchElementException {

            if ((keys && !isIntKey) || (!keys && !isIntValue)) {
                throw new NoSuchElementException("Hash Iterator");
            }

            removed = false;

            if (hasNext()) {
                counter++;

                lookup = nextLookup(lookup);

                if (keys) {
                    return intKeyTable[lookup];
                } else {
                    return intValueTable[lookup];
                }
            }

            throw new NoSuchElementException("Hash Iterator");
        }

        public long nextLong() throws NoSuchElementException {

            if ((keys && !isLongKey) || (!keys && !isLongValue)) {
                throw new NoSuchElementException("Hash Iterator");
            }

            removed = false;

            if (hasNext()) {
                counter++;

                lookup = nextLookup(lookup);

                return keys ? longKeyTable[lookup]
                            : longValueTable[lookup];
            }

            throw new NoSuchElementException("Hash Iterator");
        }

        public void remove() throws NoSuchElementException {

            if (removed) {
                throw new NoSuchElementException("Hash Iterator");
            }

            counter--;

            removed = true;

            if (BaseHashMap.this.isObjectKey) {
                if (multiValueTable == null) {
                    addOrRemove(0, 0, objectKeyTable[lookup], null, true);
                } else {
                    if (keys) {
                        addOrRemoveMultiVal(0, 0, objectKeyTable[lookup],
                                            null, true, false);
                    } else {
                        addOrRemoveMultiVal(0, 0, objectKeyTable[lookup],
                                            objectValueTable[lookup], false,
                                            true);
                    }
                }
            } else if (isIntKey) {
                addOrRemove(intKeyTable[lookup], 0, null, null, true);
            } else {
                addOrRemove(longKeyTable[lookup], 0, null, null, true);
            }

            if (isList) {
                removeRow(lookup);

                lookup--;
            }
        }

        public void setValue(Object value) {

            if (keys) {
                throw new NoSuchElementException();
            }

            objectValueTable[lookup] = value;
        }

        public int getAccessCount() {

            if (removed || accessTable == null) {
                throw new NoSuchElementException();
            }

            return accessTable[lookup];
        }

        public void setAccessCount(int count) {

            if (removed || accessTable == null) {
                throw new NoSuchElementException();
            }

            accessTable[lookup] = count;
        }

        public int getLookup() {
            return lookup;
        }
    }
}
