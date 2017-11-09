package io.arabesque.compression;

import com.koloboke.collect.set.hash.*;

import io.arabesque.computation.Computation;
import io.arabesque.embedding.Embedding;
import io.arabesque.odag.domain.Storage;
import io.arabesque.odag.domain.StorageReader;
import io.arabesque.odag.domain.StorageStats;
import io.arabesque.pattern.Pattern;
import io.arabesque.report.StorageReport;
import io.arabesque.utils.WriterSetConsumer;
import io.arabesque.utils.collection.IntArrayList;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UltraPrimitiveSimpleDomainStorage extends Storage<UltraPrimitiveSimpleDomainStorage> {
    protected boolean countsDirty;
    protected boolean keysOrdered;
    protected long[] domainCounters;
    protected int[] domain0OrderedKeys;
    protected int numberOfDomains;
    protected WriterSetConsumer writerSetConsumer;

    protected ArrayList<HashIntSet> domainEntries;

    // how many valid embeddings this storage actually have ?
    protected long numEmbeddings;
    // how many invalid embeddings this storage/partition generated
    protected long numSpuriousEmbeddings;

    protected StorageReport report;

    public UltraPrimitiveSimpleDomainStorage(int numberOfDomains) {
        setNumberOfDomains(numberOfDomains);
        countsDirty = false;
        keysOrdered = false;
        writerSetConsumer = new WriterSetConsumer();
        numEmbeddings = 0;
        numSpuriousEmbeddings = 0;
        report = new StorageReport();
    }

    public UltraPrimitiveSimpleDomainStorage() {
        numberOfDomains = -1;
        countsDirty = false;
        keysOrdered = false;
        writerSetConsumer = new WriterSetConsumer();
        numEmbeddings = 0;
        numSpuriousEmbeddings = 0;
        report = new StorageReport();
    }

    // Basic extras
    public StorageReport getStorageReport(){
        //finalizeReport()
        return report;
    }

    public long getCalculatedSizeInBytes() {
        // size of variables such as numberOfDomains, countsDirty ... etc
        long sizeInBytes = 28;

        // calc size of domain0OrderedKeys
        sizeInBytes += (domain0OrderedKeys.length * 4);

        // calc size of domainCounters
        sizeInBytes += (domainCounters.length * 8);

        // calc size of domainEntries
        sizeInBytes += getDomainEntriesCalculatedSizeInBytes();

        // calc size of writerSetConsumer

        return sizeInBytes;
    }

    public long getDomainEntriesCalculatedSizeInBytes() {
        long size = 0;
        long recordSize = 4 + 4;

        for(int i = 0 ; i < domainEntries.size() ; ++i) {
            size += (domainEntries.get(i).size() * recordSize);
        }

        return size;
    }

    public long getNumberOfWordsInDomains() {
        long count = 0L;

        for(int i = 0 ; i < domainEntries.size() ; ++i) {
            count += domainEntries.get(i).size();
        }

        return count;
    }

    public long getNumberOfWordsInConnections() { return 0; }

    protected int[] getWordIdsOfDomain(int domainId) {
        if(domainId >= numberOfDomains || domainId < 0)
            throw new ArrayIndexOutOfBoundsException("Should not access domain" + domainId + " while numOfDomains=" + numberOfDomains);

        return domainEntries.get(domainId).toIntArray();
    }

    // how many spurious embeddings this storage have ?
    public long getNumberSpuriousEmbeddings() {
        boolean countsAreDirty = countsDirty;
        if(countsAreDirty)
            countsDirty = false;

        if(countsAreDirty)
            countsDirty = true;
        else
            countsDirty = false;

        return numSpuriousEmbeddings;
    }

    // TODO: to be implemented later
    public void printAllEnumerations(String filePath) {}

    // end of basic extras

    protected synchronized void setNumberOfDomains(int numberOfDomains) {
        if (numberOfDomains == this.numberOfDomains)
            return;
        ensureCanStoreNDomains(numberOfDomains);
        this.numberOfDomains = numberOfDomains;
    }

    public ArrayList<HashIntSet> getDomainEntries() {
       return domainEntries;
    }

    public int getNumberOfEntries() {
       int numEntries = 0;
       for (HashIntSet domain: domainEntries)
          numEntries += domain.size();
       return numEntries;
    }

    private void ensureCanStoreNDomains(int nDomains) {
        if (nDomains < 0) {
            return;
        }

        if (domainEntries == null) {
            domainEntries = new ArrayList<>(nDomains);
        } else {
            domainEntries.ensureCapacity(nDomains);
        }

        int currentNumDomains = domainEntries.size();
        int delta = nDomains - currentNumDomains;

        for (int i = 0; i < delta; ++i) {
            domainEntries.add(HashIntSets.newMutableSet());
        }

        if (domainCounters == null || delta > 0) {
            domainCounters = new long[nDomains];
        }
    }

    @Override
    public void addEmbedding(Embedding embedding) {
        int numWords = embedding.getNumWords();
        IntArrayList words = embedding.getWords();

        if (domainEntries.size() != numWords) {
            throw new RuntimeException("Tried to add an embedding with wrong number " +
                    "of expected vertices (" + domainEntries.size() + ") " + embedding);
        }

        for (int i = 0; i < numWords; ++i)
            synchronized (domainEntries.get(i)) {
                domainEntries.get(i).add(words.getUnchecked(i));
            }

        countsDirty = true;
        numEmbeddings++;
    }

    /**
     * Thread-safe assuming otherBasicDomainStorage is not being concurrently
     * accessed by other threads and that there are no concurrent threads
     * affecting the same DomainEntries (i.e, each thread handles different wordIds).
     */
    @Override
    public void aggregate(UltraPrimitiveSimpleDomainStorage otherBasicDomainStorage) {
        int otherNumberOfDomains = otherBasicDomainStorage.numberOfDomains;

        if (numberOfDomains == -1)
            setNumberOfDomains(otherNumberOfDomains);

        if (numberOfDomains != otherNumberOfDomains)
            throw new RuntimeException("Different number of domains: " + numberOfDomains + " vs " + otherNumberOfDomains);

        for (int i = 0; i < numberOfDomains; ++i) {

            for(int otherWordId: otherBasicDomainStorage.domainEntries.get(i)) {
                domainEntries.get(i).add(otherWordId);
            }
        }

        countsDirty = true;
        numEmbeddings += otherBasicDomainStorage.numEmbeddings;
    }

    @Override
    public long getNumberOfEnumerations() {
        if (countsDirty) {
            /* ATTENTION: instead of an exception we return -1.
             * This way we can identify whether the odags are ready or not to be
             * read */
            //throw new RuntimeException("Should have never been the case");
            return -1;
        }

        long num = 1;
        boolean hasEnums = false;

        if (domainEntries.size() <= 0)
            return 0;

        for(HashIntSet domain: domainEntries) {
            if (domain.size() != 0)
                hasEnums = true;
            num *= domain.size();
        }

        if(!hasEnums)
            return -1;
        else
            return num;
    }

    @Override
    public void clear() {
        if (domainEntries != null)
            for (HashIntSet domain : domainEntries)
                domain.clear();

        if(domainCounters != null) {
            domainCounters = new long[numberOfDomains];
        }

        domain0OrderedKeys = null;
        countsDirty = false;
    }

    @Override
    public StorageReader getReader(Pattern pattern,
            Computation<Embedding> computation,
            int numPartitions, int numBlocks, int maxBlockSize) {
        throw new RuntimeException("Shouldn't be read");
    }

    @Override
    public StorageReader getReader(Pattern[] patterns,
            Computation<Embedding> computation,
            int numPartitions, int numBlocks, int maxBlockSize) {
        throw new RuntimeException("Shouldn't be read");
    }

    public void finalizeConstruction() {
       ExecutorService pool = Executors.newSingleThreadExecutor ();
       finalizeConstruction(pool, 1);
       pool.shutdown();
    }

    public synchronized void finalizeConstruction(ExecutorService pool, int numParts) {
        recalculateCounts();
        orderDomain0Keys();
    }

    private void recalculateCounts() {
        if (!countsDirty || numberOfDomains == 0)
            return;

        int i = numberOfDomains - 2;

        // update the counter of the last domain with one
        domainCounters[numberOfDomains - 1] = 1;

        while(i >= 0) {
            // since the counters are the cartesian products of the sizes of the following domains
            // then currentCounter = lastDomain.counter * lastDomain.size
            domainCounters[i] = domainCounters[i + 1] * domainEntries.get(i + 1).size();
            i -= 1;
        }

        countsDirty = false;
    }

    private void orderDomain0Keys() {
        if (domain0OrderedKeys != null && keysOrdered)
           return;
        domain0OrderedKeys = domainEntries.get(0).toIntArray(); //Ints.toArray(domainEntries.get(0).keySet());
        Arrays.sort(domain0OrderedKeys);
        keysOrdered = true;
    }

    @Override
    public void write(DataOutput dataOutput) throws IOException {
        dataOutput.writeLong(numEmbeddings);
        dataOutput.writeInt(numberOfDomains);

        for (HashIntSet domain : domainEntries) {
            dataOutput.writeInt(domain.size());
            for (int wordId : domain) {
                // write wordID
                dataOutput.writeInt(wordId);
            }
        }
    }

    public void write(DataOutput[] outputs, boolean[] hasContent) throws IOException {
        int numParts = outputs.length;
        int[] numEntriesOfPartsInDomain = new int[numParts];
        int partId = -1;

        for (int i = 0; i < numParts; ++i) {
            outputs[i].writeLong(numEmbeddings);
            outputs[i].writeInt(numberOfDomains);
        }

        for (HashIntSet domain : domainEntries) {
            Arrays.fill(numEntriesOfPartsInDomain, 0);

            for (int wordId : domain) {
                partId = wordId % numParts;

                ++numEntriesOfPartsInDomain[partId];
            }

            for (int i = 0; i < numParts; ++i) {
                int numEntriesOfPartInDomain = numEntriesOfPartsInDomain[i];
                outputs[i].writeInt(numEntriesOfPartInDomain);

                if (numEntriesOfPartInDomain > 0) {
                    hasContent[i] = true;
                }
            }

            for(int wordId : domain) {
                partId = wordId % numParts;

                // write wordId
                outputs[partId].writeInt(wordId);
            }
        }
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
        this.clear();
        numEmbeddings = dataInput.readLong();
        setNumberOfDomains(dataInput.readInt());

        for (int i = 0; i < numberOfDomains; ++i) {
            int domainSize = dataInput.readInt();

            for(int j = 0 ; j < domainSize ; ++j)
                domainEntries.get(i).add(dataInput.readInt());
        }

        countsDirty = true;
    }

    @Override
    public String toString() {
        return toStringDebug();
    }

    public int getNumberOfDomains() { return numberOfDomains; }

    public long getNumberOfEmbeddings() { return numEmbeddings; }

    public StorageStats getStats() {
        StorageStats stats = new StorageStats();

        stats.numDomains = domainEntries.size();

        for (HashIntSet domain : domainEntries) {
            int domainSize = domain.size();

            if (domainSize > stats.maxDomainSize)
                stats.maxDomainSize = domainSize;

            if (domainSize < stats.minDomainSize)
                stats.minDomainSize = domainSize;

            stats.sumDomainSize += domainSize;
        }

        return stats;
    }

    @Override
    public String toStringResume() {
        StringBuilder sb = new StringBuilder();
        sb.append("BasicDomainStorage{");
        sb.append("numEmbeddings=");
        sb.append(numEmbeddings);
        sb.append(",enumerations=");
        sb.append(getNumberOfEnumerations());
        sb.append(", ");

        for (int i = 0; i < domainEntries.size(); i++) {
            sb.append("Domain[" + i + "] size " + domainEntries.get(i).size());

            if (i != domainEntries.size() - 1)
                sb.append (", ");
        }
        sb.append("}");

        return sb.toString();
    }

    

    public String toStringDebug() {
        StringBuilder sb = new StringBuilder();
        sb.append("BasicDomainStorage{numEmbeddings=");
        sb.append(numEmbeddings);
        sb.append(", enumerations=");
        sb.append(getNumberOfEnumerations());
        sb.append(",");

        for(int i = 0 ; i < domainEntries.size() ; ++i) {
            sb.append("Domain[" + i + "] size " + domainEntries.get(i).size() + "\n");

            if(domainEntries.get(i).size() != 0)
                sb.append("[\n");

            int[] keys = domainEntries.get(i).toIntArray();

            for(int k = 0 ; k < keys.length ; ++k)
                sb.append(keys[k] + " ");// print the wordId

            if(domainEntries.get(i).size() != 0)
                sb.append("]\n");
        }

        sb.append("}");

        return sb.toString();
    }

    public String toJSONString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"NumStoredEmbeddings\":" + numEmbeddings + ", ");
        sb.append("\"NumEnumerations\":" + getNumberOfEnumerations() + ", ");
        sb.append("\"Domains_Sizes\": [");

        int i = 0;
        while(i < domainEntries.size()) {
            sb.append(domainEntries.get(i).size());

            if (i != domainEntries.size() - 1)
                sb.append(", ");

            i += 1;
        }

        sb.append("]");
        sb.append("}");

        return sb.toString();
    }
}
