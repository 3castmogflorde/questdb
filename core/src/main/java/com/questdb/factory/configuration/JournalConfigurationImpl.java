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

package com.questdb.factory.configuration;

import com.questdb.JournalKey;
import com.questdb.JournalMode;
import com.questdb.ex.JournalDoesNotExistException;
import com.questdb.ex.JournalException;
import com.questdb.ex.JournalMetadataException;
import com.questdb.ex.JournalWriterAlreadyOpenException;
import com.questdb.log.Log;
import com.questdb.log.LogFactory;
import com.questdb.misc.Files;
import com.questdb.misc.Os;
import com.questdb.std.ObjObjHashMap;
import com.questdb.std.ThreadLocal;
import com.questdb.std.str.CompositePath;
import com.questdb.store.Lock;
import com.questdb.store.LockManager;
import com.questdb.store.TxLog;
import com.questdb.store.UnstructuredFile;

import java.io.File;

class JournalConfigurationImpl implements JournalConfiguration {

    private static final Log LOG = LogFactory.getLog(JournalConfigurationImpl.class);

    private final static ThreadLocal<CompositePath> tlPath = new ThreadLocal<>(CompositePath.FACTORY);
    private final ObjObjHashMap<String, JournalMetadata> journalMetadata;
    private final File journalBase;

    JournalConfigurationImpl(File journalBase, ObjObjHashMap<String, JournalMetadata> journalMetadata) {
        this.journalBase = journalBase;
        this.journalMetadata = journalMetadata;
    }

    public <T> JournalMetadata<T> buildWithRootLocation(MetadataBuilder<T> builder) throws JournalException {
        File journalLocation = new File(getJournalBase(), builder.getName());

        JournalMetadata<T> mo = readMetadata(journalLocation);
        JournalMetadata<T> mn = builder.withPath(journalLocation.getAbsolutePath()).build();

        if (mo == null || mo.isCompatible(mn, false)) {
            return mn;
        }

        throw new JournalMetadataException(mo, mn);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> JournalMetadata<T> createMetadata(JournalKey<T> key) throws JournalException {
        File journalLocation = new File(getJournalBase(), key.getName());
        String path = journalLocation.getAbsolutePath();

        JournalMetadata<T> mo = readMetadata(journalLocation);
        String className = key.getModelClassName();
        JournalMetadata<T> mn = className == null ? null : journalMetadata.get(className);

        if (mo == null) {
            // no existing journal
            // can create one on either of two conditions:
            // 1. we have new metadata to create journal from
            // 2. key represents a class that can be introspected

            if (mn == null && key.getModelClass() == null) {
                LOG.error().$("Journal does not exist: ").$(key.getName()).$();
                throw JournalDoesNotExistException.INSTANCE;
            }

            MetadataBuilder<T> builder;

            if (mn == null) {
                builder = new JournalMetadataBuilder<>(key.getModelClass(), key.getName());
            } else {
                if (key.getModelClass() == null) {
                    builder = (MetadataBuilder<T>) new JournalStructure(mn, key.getName());
                } else {
                    builder = new JournalMetadataBuilder<>(mn, key.getName());
                }
            }
            return builder.
                    partitionBy(key.getPartitionBy()).
                    recordCountHint(key.getRecordHint()).
                    withPath(path).
                    ordered(key.isOrdered()).
                    build();
        } else {
            // journal exists on disk
            if (mn == null) {
                // we have on-disk metadata and no in-app meta
                if (key.getModelClass() == null) {
                    // if this is generic access request
                    // return metadata as is, nothing more to do
                    return (JournalMetadata<T>) new JournalStructure(mo, key.getName()).withPath(path).recordCountHint(key.getRecordHint()).build();
                }
                // if this is request to map class on existing journal
                // check compatibility and map to class (calc offsets and constructor)
                return new JournalStructure(mo, key.getName()).withPath(path).recordCountHint(key.getRecordHint()).map(key.getModelClass());
            }

            // we have both on-disk and in-app meta
            // check if in-app meta matches on-disk meta
            if (mn.isCompatible(mo, false)) {
                if (mn.getModelClass() == null) {
                    return (JournalMetadata<T>) new JournalStructure(mn).recordCountHint(key.getRecordHint()).withPath(path).build();
                }
                return new JournalMetadataBuilder<>(mn, key.getName()).withPath(path).recordCountHint(key.getRecordHint()).build();
            }

            throw new JournalMetadataException(mo, mn);
        }
    }

    @Override
    public void delete(CharSequence location) throws JournalException {
        File l = new File(journalBase, location.toString());
        Lock lock = LockManager.lockExclusive(l.getAbsolutePath());
        try {
            if (lock == null || !lock.isValid()) {
                LOG.error().$("Cannot obtain lock on ").$(l).$();
                throw JournalWriterAlreadyOpenException.INSTANCE;
            }
            Files.deleteOrException(l);
        } finally {
            LockManager.release(lock);
        }
    }

    public int exists(CharSequence location) {
        CompositePath path = tlPath.get();
        String base = getJournalBase().getAbsolutePath();

        if (!Files.exists(path.of(base).concat(location).$())) {
            return JournalConfiguration.DOES_NOT_EXIST;
        }

        if (Files.exists(path.of(base).concat(location).concat(TxLog.FILE_NAME).$())
                && Files.exists(path.of(base).concat(location).concat(JournalConfiguration.FILE_NAME).$())) {
            return JournalConfiguration.EXISTS;
        }

        return JournalConfiguration.EXISTS_FOREIGN;
    }

    @Override
    public File getJournalBase() {
        return journalBase;
    }

    @Override
    public void rename(CharSequence location, CharSequence to) throws JournalException {
        try (CompositePath oldName = new CompositePath()) {
            try (CompositePath newName = new CompositePath()) {
                String path = journalBase.getAbsolutePath();

                oldName.of(path).concat(location).$();
                newName.of(path).concat(to).$();

                if (!Files.exists(oldName)) {
                    LOG.error().$("Journal does not exist: ").$(oldName).$();
                    throw JournalDoesNotExistException.INSTANCE;
                }

                if (Os.type == Os.WINDOWS) {
                    oldName.of("\\\\?\\").concat(path).concat(location).$();
                    newName.of("\\\\?\\").concat(path).concat(to).$();
                }


                Lock lock = LockManager.lockExclusive(oldName.toString());
                try {
                    if (lock == null || !lock.isValid()) {
                        LOG.error().$("Cannot obtain lock on ").$(oldName).$();
                        throw JournalWriterAlreadyOpenException.INSTANCE;
                    }

                    if (Files.exists(newName)) {
                        throw new JournalException("Destination directory already exists");
                    }


                    Lock writeLock = LockManager.lockExclusive(newName.toString());
                    try {

                        if (writeLock == null || !writeLock.isValid()) {
                            LOG.error().$("Cannot obtain lock on ").$(newName).$();
                            throw JournalWriterAlreadyOpenException.INSTANCE;
                        }

                        if (!Files.rename(oldName, newName)) {
                            throw new JournalException("Cannot rename journal: %s [%d]", oldName, Os.errno());
                        }
                    } finally {
                        LockManager.release(writeLock);
                    }
                } finally {
                    LockManager.release(lock);
                }
            }
        }
    }

    private <T> JournalMetadata<T> readMetadata(File location) throws JournalException {
        if (location.exists()) {
            File metaFile = new File(location, FILE_NAME);
            if (!metaFile.exists()) {
                throw new JournalException(location + " is not a recognised journal");
            }

            try (UnstructuredFile hb = new UnstructuredFile(metaFile, 12, JournalMode.READ)) {
                return new JournalMetadata<>(hb);
            }
        }
        return null;
    }
}
