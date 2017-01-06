/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.ql.impl;

import com.questdb.Journal;
import com.questdb.Partition;
import com.questdb.ex.JournalException;
import com.questdb.ex.JournalRuntimeException;
import com.questdb.factory.ReaderFactory;
import com.questdb.factory.configuration.JournalMetadata;
import com.questdb.ql.PartitionCursor;
import com.questdb.ql.PartitionSlice;
import com.questdb.ql.PartitionSource;
import com.questdb.ql.StorageFacade;
import com.questdb.std.AbstractImmutableIterator;
import com.questdb.std.str.CharSink;
import com.questdb.std.str.FileNameExtractorCharSequence;

public class JournalPartitionSource extends AbstractImmutableIterator<PartitionSlice> implements PartitionSource, PartitionCursor {

    private final boolean open;
    private final PartitionSlice slice = new PartitionSlice();
    private final JournalMetadata metadata;
    private final MasterStorageFacade storageFacade = new MasterStorageFacade();
    private Journal journal;
    private int partitionCount;
    private int partitionIndex;

    public JournalPartitionSource(JournalMetadata metadata, boolean open) {
        this.metadata = metadata;
        this.open = open;
    }

    @Override
    public JournalMetadata getMetadata() {
        return metadata;
    }

    @Override
    @SuppressWarnings("unchecked")
    public PartitionCursor prepareCursor(ReaderFactory factory) {
        try {
            this.journal = factory.reader(metadata);
            this.journal.refresh();
        } catch (JournalException e) {
            throw new JournalRuntimeException(e);
        }
        storageFacade.of(journal.getMetadata());
        partitionCount = journal.getPartitionCount();
        partitionIndex = 0;
        return this;
    }

    @Override
    public Partition getPartition(int index) {
        try {
            return journal.getPartition(index, true);
        } catch (JournalException e) {
            throw new JournalRuntimeException(e);
        }
    }

    @Override
    public StorageFacade getStorageFacade() {
        return storageFacade;
    }

    @Override
    public void toTop() {
        this.partitionIndex = 0;
    }

    @Override
    public boolean hasNext() {
        return partitionIndex < partitionCount;
    }

    @Override
    public PartitionSlice next() {
        try {
            slice.partition = journal.getPartition(partitionIndex++, open);
            slice.lo = 0;
            slice.calcHi = true;
            return slice;
        } catch (JournalException e) {
            throw new JournalRuntimeException(e);
        }
    }

    @Override
    public void toSink(CharSink sink) {
        sink.put('{');
        sink.putQuoted("op").put(':').putQuoted("JournalPartitionSource").put(',');
        sink.putQuoted("journal").put(':').putQuoted(FileNameExtractorCharSequence.get(metadata.getPath()));
        sink.put('}');
    }
}
