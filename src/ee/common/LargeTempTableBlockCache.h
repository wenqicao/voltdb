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

#ifndef VOLTDB_LARGETEMPTABLEBLOCKCACHE_H
#define VOLTDB_LARGETEMPTABLEBLOCKCACHE_H

#include <deque>
#include <list>
#include <map>
#include <utility>
#include <vector>

#include <boost/scoped_array.hpp>

#include "storage/LargeTempTableBlock.h"
#include "common/types.h"

class LargeTempTableTest_OverflowCache;

namespace voltdb {

class LargeTempTable;


/**
 * There is one instance of this class for each EE instance (one per
 * thread).
 *
 * This class keeps track of tuple blocks (and associated pools
 * containing variable length data) for all large temp tables
 * currently in use.
 */
class LargeTempTableBlockCache {

    friend class ::LargeTempTableTest_OverflowCache;

 public:

    /**
     * Construct an instance of a cache containing zero large temp
     * table blocks.
     */
    LargeTempTableBlockCache(int64_t maxCacheSizeInBytes);

    /**
     * A do-nothing destructor
     */
    ~LargeTempTableBlockCache();

    /** Get a new empty block for the supplied table.  Returns the id
        of the new block and the new block. */
    std::pair<int64_t, LargeTempTableBlock*> getEmptyBlock(LargeTempTable* ltt);

    /** "Unpin" the specified block, i.e., mark it as a candidate to
        store to disk when the cache becomes full. */
    void unpinBlock(int64_t blockId);

    /** Returns true if the block is pinned. */
    bool blockIsPinned(int64_t blockId) const;

    /** Fetch (and pin) the specified block, loading it from disk if
        necessary.  */
    LargeTempTableBlock* fetchBlock(int64_t blockId);

    /** The large temp table for this block is being destroyed, so
        release all resources associated with this block. */
    void releaseBlock(int64_t blockId);

    /** Called from LargeTempTableBlock.  Report an increase in the
        amount of memory in use by the cache, and store a block to
        disk if necessary to make more room.  Argument represents the
        amount of new memory now in use. */
    void increaseAllocatedMemory(int64_t numBytes);

    /** Called from LargeTempTableBlock destructor. */
    void decreaseAllocatedMemory(int64_t numBytes);

    /** The number of pinned (blocks currently being inserted into or
        scanned) entries in the cache */
    size_t numPinnedEntries() const {
        size_t cnt = 0;
        BOOST_FOREACH(auto &block, m_blockList) {
            if (block->isPinned()) {
                ++cnt;
            }
        }

        return cnt;
    }

    /** The number of blocks that are cached in memory (as opposed to
        stored on disk) */
    size_t residentBlockCount() const {
        size_t count = 0;
        BOOST_FOREACH(auto &block, m_blockList) {
            if (block->isResident()) {
                ++count;
            }
        }

        return count;
    }

    /** The total number of large temp table blocks, both cached in
        memory and stored on disk */
    size_t totalBlockCount() const {
        return m_blockList.size();
    }

    /** The number of bytes (in large temp table tuple blocks and
        associated string pool data) in blocks that are cached in
        memory */
    int64_t allocatedMemory() const {
        return m_totalAllocatedBytes;
    }

    /** The max size that the cache can grow to.  If we insert a tuple
        or allocate a new block and exceed this amount, we need to
        store an unpinned block to disk. */
    int64_t maxCacheSizeInBytes() const {
        return m_maxCacheSizeInBytes;
    }

    /** Release all large temp table blocks (both resident and stored
        on disk) */
    void releaseAllBlocks();

    /** Return a string containing useful debug information */
    std::string debug() const;

 private:

    // This at some point may need to be unique across the entire process
    int64_t getNextId() {
        int64_t nextId = m_nextId;
        ++m_nextId;
        return nextId;
    }

    // Stores the least recently used block to disk.
    void storeABlock();

    const int64_t m_maxCacheSizeInBytes;

    typedef std::list<std::unique_ptr<LargeTempTableBlock>> BlockList;

    // The front of the block list are the most recently used blocks.
    // The tail will be the least recently used blocks.
    // The tail of the list should have no pinned blocks.
    BlockList m_blockList;
    std::map<int64_t, BlockList::iterator> m_idToBlockMap;

    int64_t m_nextId;
    int64_t m_totalAllocatedBytes;
};

}

#endif // VOLTDB_LARGETEMPTABLEBLOCKCACHE_H
