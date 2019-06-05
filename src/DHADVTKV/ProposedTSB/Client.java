package DHADVTKV.ProposedTSB;

import DHADVTKV.common.Channel;
import DHADVTKV.common.DataObject;
import DHADVTKV.ProposedTSB.messages.*;
import peersim.core.CommonState;

import java.util.*;
import java.util.stream.Collectors;

import static DHADVTKV.common.Configurations.UNDEFINED;

class Client {

    private long clock;
    private long transactionID;
    private long snapshot;
    private int nodeId;
    private List<DataObject> gets;
    private List<DataObject> puts;
    private boolean inTransaction;

    Client(int nodeId) {
        this.clock = 0;
        this.transactionID = UNDEFINED;
        this.snapshot = UNDEFINED;
        this.nodeId = nodeId;
        this.gets = new ArrayList<>();
        this.puts = new ArrayList<>();
        this.inTransaction = false;
    }

    void beginTransaction() {
        if (this.inTransaction) throw new RuntimeException("Trying to start transaction when it's already in course");
        this.inTransaction = true;
        this.transactionID = CommonState.r.nextLong();
    }

    void get(int node, long key) {
        long version = this.snapshot;
        if (version == UNDEFINED) {
            version = this.clock;
        }
        Channel.sendMessage(new TransactionalGet(nodeId, node, key, version));
    }

    DataObject onTransactionalGetResponse(TransactionalGetResponse response) {
        maybeSetSnapshot(response);
        gets.add(response.getObject());
        if (checkGetConflict(response)) {
            cleanState();
            return null;
        }
        return response.getObject();
    }

    void put(int partition, long key, long value) {
        DataObject object;
        if (snapshot == UNDEFINED) {
           object = createObject(partition, key, value, transactionID, clock + 1);
        } else {
            object = createObject(partition, key, value, transactionID, snapshot + 1);
        }
        puts.add(object);
    }

    void commit() {
        Map<Integer, List<DataObject>> nodeGets = new HashMap<>();
        Map<Integer, List<DataObject>> nodePuts = new HashMap<>();

        for (DataObject dataObject : gets) {
            nodeGets.computeIfAbsent(dataObject.getNode(), k -> new ArrayList<>()).add(dataObject);
        }
        for (DataObject dataObject : puts) {
            nodePuts.computeIfAbsent(dataObject.getNode(), k -> new ArrayList<>()).add(dataObject);
        }

        Set<Integer> nodes = new HashSet<>();
        nodes.addAll(nodeGets.keySet());
        nodes.addAll(nodePuts.keySet());

        for (Integer node : nodes) {
            Channel.sendMessage(new CommitTransaction(
                    nodeId,
                    node,
                    transactionID,
                    snapshot == UNDEFINED ? clock : snapshot,
                    nodeGets.getOrDefault(node, new ArrayList<>()).stream().map(DataObject::getKey).collect(Collectors.toList()),
                    nodePuts.getOrDefault(node, new ArrayList<>()),
                    nodes.size()
            ));
        }
    }

    boolean onTransactionCommitResult(TransactionCommitResult response) {
            this.clock = response.getLsn();
            cleanState();
            return !response.isConflicts();
    }

    private void maybeSetSnapshot(TransactionalGetResponse response) {
        long objectVersion = response.getObject().getMetadata().getOrDefault("version", UNDEFINED);
        if (snapshot == UNDEFINED && objectVersion != UNDEFINED) {
            snapshot = Math.max(objectVersion, clock);
        }
    }

    private boolean checkGetConflict(TransactionalGetResponse response) {
        return response.getObject().getMetadata().getOrDefault("version", UNDEFINED) > snapshot;
    }

    private DataObject createObject(int node, long key, long value, long transactionID, long tentativeVersion) {
        Map<String, Long> metadata = new HashMap<>();
        metadata.put("transactionID", transactionID);
        metadata.put("tentativeVersion", tentativeVersion);
        return new DataObject(node, key, value, metadata);
    }

    private void cleanState() {
        this.inTransaction = false;
        this.transactionID = UNDEFINED;
        this.snapshot = UNDEFINED;
        this.gets = new ArrayList<>();
        this.puts = new ArrayList<>();
    }
}
