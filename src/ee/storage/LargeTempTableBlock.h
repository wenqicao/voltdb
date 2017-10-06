/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

#ifndef VOLTDB_LARGETEMPTABLEBLOCK_HPP
#define VOLTDB_LARGETEMPTABLEBLOCK_HPP

#include <utility>

#include "storage/TupleBlock.h"

namespace voltdb {

class LargeTempTable;

/**
 * A wrapper around a buffer of memory used to store tuples.
 *
 * The lower-addressed memory of the buffer is used to store tuples of
 * fixed size, similar to persistent table blocks.  The
 * higher-addressed memory stores outlined, variable-length objects
 * referenced in the tuples.
 *
 * As the block is inserted into tuple and outlined memory grow
 * towards the middle of the buffer.  The buffer is full when there is
 * not enough room in the middle of the buffer for the next tuple.
 *
 * This block layout is chosen so that the whole block may be written
 * to disk as a self-contained unit, and reloaded later (since block
 * may be at a different memory address, pointers to outlined data in
 * the tuples will need to be updated).
 */
class LargeTempTableBlock {
 public:

    /** The size of all large temp table blocks */
    static const size_t BLOCK_SIZE_IN_BYTES = 8 * 1024 * 1024; // 8 MB

    /** constructor for a new block. */
    LargeTempTableBlock(int64_t id, LargeTempTable* ltt);

    /** Return the unique ID for this block */
    int64_t id() const {
        return m_id;
    }

    /** insert another tuple into this block */
    bool insertTuple(const TableTuple& source);

    /** Because we can allocate outlined objects into LTT blocks,
        this class needs to function like a pool, and this allocate
        method provides this. */
    void* allocate(std::size_t size);

    /** Return the ordinal position of the next free slot in this
        block. */
    uint32_t unusedTupleBoundary() {
        return m_activeTupleCount;
    }

    /** Return a pointer to the storage for this block. */
    char* address() {
        return m_storage.get();
    }

    /** Returns the amount of memory used by this block.  For blocks
        that are resident (not stored to disk) this will return
        BLOCK_SIZE_IN_BYTES, and zero otherwise.
        Note that this value may not be equal to
        getAllocatedTupleMemory() + getAllocatedPoolMemory() because
        of unused space at the middle of the block. */
    int64_t getAllocatedMemory() const;

    /** Return the number of bytes used to store tuples in this
        block */
    int64_t getAllocatedTupleMemory() const;

    /** Return the number of bytes used to store outlined objects in
        this block. */
    int64_t getAllocatedPoolMemory() const;

    /** Release the storage associated with this block (so it can be
        persisted to disk) */
    std::unique_ptr<char[]> releaseData();

    /** Set the storage associated with this block (as when loading
        from disk) */
    void setData(std::unique_ptr<char[]> storage);

    /** Returns true if this block is pinned in the cache and may not
        be stored to disk (i.e., we are currently inserting tuples
        into or iterating over the tuples in this block)  */
    bool isPinned() const {
        return m_isPinned;
    }

    /** Mark this block as pinned and un-evictable */
    void pin() {
        assert(!m_isPinned);
        m_isPinned = true;
    }

    /** Mark this block as unpinned and evictable */
    void unpin() {
        assert(m_isPinned);
        m_isPinned = false;
    }

    /** Returns true if this block is currently loaded into memory */
    bool isResident() const {
        return m_storage.get() != NULL;
    }

    /** Return the number of tuples in this block */
    int64_t activeTupleCount() const {
        return m_activeTupleCount;
    }

 private:

    /** the ID of this block */
    int64_t m_id;

    /** Pointer to block storage */
    std::unique_ptr<char[]> m_storage;

    /** Points the address where the next tuple will be inserted */
    char* m_tupleInsertionPoint;

    /** Points to the address where the next outlined object will be inserted */
    char* m_outlinedInsertionPoint;

    /** True if this object cannot be evicted from the LTT block cache
        and stored to disk */
    bool m_isPinned;

    /** Number of tuples currently in this block */
    int64_t m_activeTupleCount;
};

} // end namespace voltdb

#endif // VOLTDB_LARGETEMPTABLEBLOCK_HPP
