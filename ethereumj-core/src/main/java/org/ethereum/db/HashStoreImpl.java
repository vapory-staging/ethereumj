package org.ethereum.db;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.Predicate;
import org.ethereum.datasource.mapdb.MapDBFactory;
import org.ethereum.util.FastByteComparisons;
import org.mapdb.DB;
import org.mapdb.Serializer;

import java.util.*;

import static org.ethereum.config.SystemProperties.CONFIG;

/**
 * @author Mikhail Kalinin
 * @since 07.07.2015
 */
public class HashStoreImpl implements HashStore {

    private final static String STORE_NAME = "hashstore";
    private MapDBFactory mapDBFactory;

    private DB db;
    private Map<Long, byte[]> hashes;
    private List<Long> index;

    @Override
    public void open() {
        db = mapDBFactory.createTransactionalDB(dbName());
        hashes = db.hashMapCreate(STORE_NAME)
                .keySerializer(Serializer.LONG)
                .valueSerializer(Serializer.BYTE_ARRAY)
                .makeOrGet();
        index = new ArrayList<>(hashes.keySet());
        sortIndex();
    }

    private String dbName() {
        return String.format("%s/%s", STORE_NAME, STORE_NAME);
    }

    @Override
    public void close() {
        db.close();
    }

    @Override
    public void add(byte[] hash) {
        addInner(false, hash);
        db.commit();
    }

    @Override
    public void addFirst(byte[] hash) {
        addInner(true, hash);
        db.commit();
    }

    @Override
    public void addFirstBatch(Collection<byte[]> hashes) {
        for (byte[] hash : hashes) {
            addInner(true, hash);
        }
        db.commit();
    }

    private synchronized void addInner(boolean first, byte[] hash) {
        Long idx = createIndex(first);
        hashes.put(idx, hash);
    }

    @Override
    public byte[] peek() {
        synchronized (this) {
            if(index.isEmpty()) {
                return null;
            }

            Long idx = index.get(0);
            return hashes.get(idx);
        }
    }

    @Override
    public byte[] poll() {
        byte[] hash = pollInner();
        db.commit();
        return hash;
    }

    @Override
    public List<byte[]> pollBatch(int qty) {
        if(index.isEmpty()) {
            return Collections.emptyList();
        }
        List<byte[]> hashes = new ArrayList<>(qty > size() ? qty : size());
        while (hashes.size() < qty) {
            byte[] hash = pollInner();
            if(hash == null) {
                break;
            }
            hashes.add(hash);
        }
        db.commit();
        return hashes;
    }

    private byte[] pollInner() {
        byte[] hash;
        synchronized (this) {
            if(index.isEmpty()) {
                return null;
            }

            Long idx = index.get(0);
            hash = hashes.get(idx);
            hashes.remove(idx);
            index.remove(0);
        }
        return hash;
    }

    @Override
    public boolean isEmpty() {
        return index.isEmpty();
    }

    @Override
    public Set<Long> getKeys() {
        return hashes.keySet();
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public void clear() {
        synchronized (this) {
            index.clear();
            hashes.clear();
        }
        db.commit();
    }

    @Override
    public void removeAll(Collection<byte[]> removing) {
        Set<Long> removed = new HashSet<>();
        for(final Map.Entry<Long, byte[]> e : hashes.entrySet()) {
            byte[] hash = CollectionUtils.find(removing, new Predicate<byte[]>() {
                @Override
                public boolean evaluate(byte[] hash) {
                    return FastByteComparisons.compareTo(hash, 0, 32, e.getValue(), 0, 32) == 0;
                }
            });
            if(hash != null) {
                removed.add(e.getKey());
            }
        }
        index.removeAll(removed);
        for(Long idx : removed) {
            hashes.remove(idx);
        }
        db.commit();
    }

    private Long createIndex(boolean first) {
        Long idx;
        if(index.isEmpty()) {
            idx = 0L;
            index.add(idx);
        } else if(first) {
            idx = index.get(0) - 1;
            index.add(0, idx);
        } else {
            idx = index.get(index.size() - 1) + 1;
            index.add(idx);
        }
        return idx;
    }

    private void sortIndex() {
        Collections.sort(index);
    }

    public void setMapDBFactory(MapDBFactory mapDBFactory) {
        this.mapDBFactory = mapDBFactory;
    }
}