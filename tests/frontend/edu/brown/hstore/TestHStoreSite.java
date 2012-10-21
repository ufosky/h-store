package edu.brown.hstore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;
import org.voltdb.ClientResponseDebug;
import org.voltdb.ClientResponseImpl;
import org.voltdb.ParameterSet;
import org.voltdb.SysProcSelector;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Site;
import org.voltdb.client.Client;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.sysprocs.Statistics;
import org.voltdb.utils.EstTime;
import org.voltdb.utils.VoltTableUtil;

import edu.brown.BaseTestCase;
import edu.brown.benchmark.tm1.procedures.GetNewDestination;
import edu.brown.benchmark.tm1.procedures.UpdateLocation;
import edu.brown.hstore.Hstoreservice.Status;
import edu.brown.hstore.callbacks.MockClientCallback;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.txns.LocalTransaction;
import edu.brown.hstore.util.TransactionCounter;
import edu.brown.pools.TypedObjectPool;
import edu.brown.pools.TypedPoolableObjectFactory;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.EventObservable;
import edu.brown.utils.EventObserver;
import edu.brown.utils.PartitionSet;
import edu.brown.utils.ProjectType;
import edu.brown.utils.StringUtil;
import edu.brown.utils.ThreadUtil;

public class TestHStoreSite extends BaseTestCase {
    
    private static final Class<? extends VoltProcedure> TARGET_PROCEDURE = GetNewDestination.class;
    private static final long CLIENT_HANDLE = 1l;
    private static final int NUM_PARTITIONS = 2;
    private static final int BASE_PARTITION = 0;
    
    private HStoreSite hstore_site;
    private HStoreSite.Debug hstore_debug;
    private HStoreObjectPools objectPools;
    private HStoreConf hstore_conf;
    private TransactionQueueManager.Debug queue_debug;
    private Client client;

    private static final ParameterSet PARAMS = new ParameterSet(
        new Long(0), // S_ID
        new Long(1), // SF_TYPE
        new Long(2), // START_TIME
        new Long(3)  // END_TIME
    );

    
    @Before
    public void setUp() throws Exception {
        super.setUp(ProjectType.TM1);
        initializeCatalog(1, 1, NUM_PARTITIONS);
     
        for (TransactionCounter tc : TransactionCounter.values()) {
            tc.clear();
        } // FOR
        
        Site catalog_site = CollectionUtil.first(catalogContext.sites);
        this.hstore_conf = HStoreConf.singleton();
        this.hstore_conf.site.pool_profiling = true;
        this.hstore_conf.site.status_enable = false;
        this.hstore_conf.site.anticache_enable = false;
        
        this.hstore_site = createHStoreSite(catalog_site, hstore_conf);
        this.objectPools = this.hstore_site.getObjectPools();
        this.hstore_debug = this.hstore_site.getDebugContext();
        this.queue_debug = this.hstore_site.getTransactionQueueManager().getDebugContext();
        this.client = createClient();
    }
    
    @Override
    protected void tearDown() throws Exception {
        if (this.client != null) this.client.close();
        if (this.hstore_site != null) this.hstore_site.shutdown();
    }
    
    /**
     * testClientResponseDebug
     */
    @Test
    public void testClientResponseDebug() throws Exception {
         hstore_conf.site.txn_client_debug = true;
        
        // Submit a transaction and check that our ClientResponseDebug matches
        // what the transaction was initialized with
        final Map<Long, LocalTransaction> copiedHandles = new HashMap<Long, LocalTransaction>(); 
        EventObserver<LocalTransaction> newTxnObserver = new EventObserver<LocalTransaction>() {
            @Override
            public void update(EventObservable<LocalTransaction> o, LocalTransaction ts) {
                LocalTransaction copy = new LocalTransaction(hstore_site);
                copy.init(ts.getTransactionId(),
                          ts.getInitiateTime(),
                          ts.getClientHandle(),
                          ts.getBasePartition(),
                          new PartitionSet(ts.getPredictTouchedPartitions()),
                          ts.isPredictReadOnly(),
                          ts.isPredictAbortable(),
                          ts.getProcedure(),
                          ts.getProcedureParameters(),
                          null);
                copiedHandles.put(ts.getTransactionId(), copy);
            }
        };
        hstore_site.getTransactionInitializer().newTxnObservable.addObserver(newTxnObserver);
        
        Procedure catalog_proc = this.getProcedure(UpdateLocation.class);
        Object params[] = { 1234l, "XXXX" };
        ClientResponse cr = this.client.callProcedure(catalog_proc.getName(), params);
        assertEquals(Status.OK, cr.getStatus());
        // System.err.println(cr);
        // System.err.println(StringUtil.formatMaps(copiedHandles));
        
        assertTrue(cr.hasDebug());
        ClientResponseDebug crDebug = cr.getDebug();
        assertNotNull(crDebug);
        
        LocalTransaction copy = copiedHandles.get(cr.getTransactionId());
        assertNotNull(copiedHandles.toString(), copy);
        assertEquals(copy.getTransactionId().longValue(), cr.getTransactionId());
        assertEquals(copy.getClientHandle(), cr.getClientHandle());
        assertEquals(copy.getBasePartition(), cr.getBasePartition());
        assertEquals(copy.isPredictAbortable(), crDebug.isPredictAbortable());
        assertEquals(copy.isPredictReadOnly(), crDebug.isPredictReadOnly());
        assertEquals(copy.isPredictSinglePartition(), crDebug.isPredictSinglePartition());
        assertEquals(copy.getPredictTouchedPartitions(), crDebug.getPredictTouchedPartitions());
    }
    
    /**
     * testTransactionCounters
     */
    @Test
    public void testTransactionCounters() throws Exception {
        hstore_conf.site.txn_counters = true;
        hstore_site.updateConf(hstore_conf);
        
        Procedure catalog_proc = this.getProcedure(UpdateLocation.class);
        ClientResponse cr = null;
        int num_txns = 500;
        
        Object params[] = { 1234l, "XXXX" };
        for (int i = 0; i < num_txns; i++) {
            this.client.callProcedure(catalog_proc.getName(), params);
        } // FOR
        ThreadUtil.sleep(1000);
        assertEquals(num_txns, TransactionCounter.RECEIVED.get());
        
        // Now try invoking @Statistics to get back more information
        params = new Object[]{ SysProcSelector.TXNCOUNTER.name(), 0 };
        cr = this.client.callProcedure(VoltSystemProcedure.procCallName(Statistics.class), params);
//        System.err.println(cr);
        assertNotNull(cr);
        assertEquals(Status.OK, cr.getStatus());
        
        VoltTable results[] = cr.getResults();
        assertEquals(1, results.length);
        boolean found = false;
        while (results[0].advanceRow()) {
            if (results[0].getString(3).equalsIgnoreCase(catalog_proc.getName())) {
                for (int i = 4; i < results[0].getColumnCount(); i++) {
                    String counterName = results[0].getColumnName(i);
                    TransactionCounter tc = TransactionCounter.get(counterName);
                    assertNotNull(counterName, tc);
                    
                    Long tcVal = tc.get(catalog_proc);
                    if (tcVal == null) tcVal = 0l;
                    assertEquals(counterName, tcVal.intValue(), (int)results[0].getLong(i));
                } // FOR
                found = true;
                break;
            }
        } // WHILE
        assertTrue(found);
    }
    
    /**
     * testTransactionProfilers
     */
    @Test
    public void testTransactionProfilers() throws Exception {
        hstore_conf.site.txn_counters = true;
        hstore_conf.site.txn_profiling = true;
        hstore_site.updateConf(hstore_conf);
        
        Procedure catalog_proc = this.getProcedure(UpdateLocation.class);
        ClientResponse cr = null;
        int num_txns = 500;
        
        Object params[] = { 1234l, "XXXX" };
        for (int i = 0; i < num_txns; i++) {
            this.client.callProcedure(catalog_proc.getName(), params);
        } // FOR
        ThreadUtil.sleep(1000);
        assertEquals(num_txns, TransactionCounter.RECEIVED.get());
        
        // Now try invoking @Statistics to get back more information
        params = new Object[]{ SysProcSelector.TXNPROFILER.name(), 0 };
        cr = this.client.callProcedure(VoltSystemProcedure.procCallName(Statistics.class), params);
//        System.err.println(cr);
        System.err.println(VoltTableUtil.format(cr.getResults()[0]));
        assertNotNull(cr);
        assertEquals(Status.OK, cr.getStatus());
        
        String fields[] = { "TOTAL", "INIT_TOTAL" };
        
        VoltTable results[] = cr.getResults();
        assertEquals(1, results.length);
        boolean found = false;
        results[0].resetRowPosition();
        while (results[0].advanceRow()) {
            if (results[0].getString(3).equalsIgnoreCase(catalog_proc.getName())) {
                for (String f : fields) {
                    int i = results[0].getColumnIndex(f);
                    assertEquals(f, results[0].getColumnName(i));
                    long val = results[0].getLong(i);
                    assertFalse(f, results[0].wasNull());
                    assertTrue(f, val > 0);
                } // FOR
                found = true;
                break;
            }
        } // WHILE
        assertTrue(found);
    }
    
    /**
     * testAbortReject
     */
    @Test
    public void testAbortReject() throws Exception {
        // Check to make sure that we reject a bunch of txns that all of our
        // handles end up back in the object pool. To do this, we first need
        // to set the PartitionExecutor's to reject all incoming txns
        hstore_conf.site.queue_incoming_max_per_partition = 1;
        hstore_conf.site.queue_incoming_release_factor = 0;
        hstore_conf.site.queue_incoming_increase = 0;
        hstore_conf.site.queue_incoming_increase_max = 0;
        hstore_site.updateConf(hstore_conf);

        final Set<LocalTransaction> expectedHandles = new HashSet<LocalTransaction>(); 
        final List<Long> expectedIds = new ArrayList<Long>();
        
        EventObserver<LocalTransaction> newTxnObserver = new EventObserver<LocalTransaction>() {
            @Override
            public void update(EventObservable<LocalTransaction> o, LocalTransaction ts) {
                expectedHandles.add(ts);
                assertFalse(ts.toString(), expectedIds.contains(ts.getTransactionId()));
                expectedIds.add(ts.getTransactionId());
            }
        };
        hstore_site.getTransactionInitializer().newTxnObservable.addObserver(newTxnObserver);
        
        // We need to get at least one ABORT_REJECT
        final int num_txns = 500;
        final CountDownLatch latch = new CountDownLatch(num_txns);
        final AtomicInteger numAborts = new AtomicInteger(0);
        final List<Long> actualIds = new ArrayList<Long>();
        
        ProcedureCallback callback = new ProcedureCallback() {
            @Override
            public void clientCallback(ClientResponse cr) {
                if (cr.getStatus() == Status.ABORT_REJECT) {
                    numAborts.incrementAndGet();
                }
                if (cr.getTransactionId() > 0) actualIds.add(cr.getTransactionId());
                latch.countDown();
            }
        };
        
        // Then blast out a bunch of txns that should all come back as rejected
        Procedure catalog_proc = this.getProcedure(UpdateLocation.class);
        Object params[] = { 1234l, "XXXX" };
        for (int i = 0; i < num_txns; i++) {
            this.client.callProcedure(callback, catalog_proc.getName(), params);
        } // FOR
        
        boolean result = latch.await(20, TimeUnit.SECONDS);
//        System.err.println("InflightTxnCount: " + hstore_debug.getInflightTxnCount());
//        System.err.println("DeletableTxnCount: " + hstore_debug.getDeletableTxnCount());
//        System.err.println("--------------------------------------------");
//        System.err.println("EXPECTED IDS:");
//        System.err.println(StringUtil.join("\n", CollectionUtil.sort(expectedIds)));
//        System.err.println("--------------------------------------------");
//        System.err.println("ACTUAL IDS:");
//        System.err.println(StringUtil.join("\n", CollectionUtil.sort(actualIds)));
//        System.err.println("--------------------------------------------");
        
        assertTrue("Timed out [latch="+latch.getCount() + "]", result);
        assertNotSame(0, numAborts.get());
        assertNotSame(0, expectedHandles.size());
        assertNotSame(0, expectedIds.size());
        assertNotSame(0, actualIds.size());
        
        // HACK: Wait a little to know that the periodic thread has attempted
        // to clean-up our deletable txn handles
        ThreadUtil.sleep(2500);

        assertEquals(0, hstore_debug.getInflightTxnCount());
        assertEquals(0, hstore_debug.getDeletableTxnCount());
        
        // Make sure that there is nothing sitting around in our queues
        assertEquals("INIT", 0, queue_debug.getInitQueueSize());
        assertEquals("BLOCKED", 0, queue_debug.getBlockedQueueSize());
        assertEquals("RESTART", 0, queue_debug.getRestartQueueSize());
        
        // Check to make sure that all of our handles are not initialized
        for (LocalTransaction ts : expectedHandles) {
            assertNotNull(ts);
            if (ts.isInitialized()) {
                System.err.println(ts.debug());
            }
            assertFalse(ts.debug(), ts.isInitialized());
        } // FOR
        
        // Then check to make sure that there aren't any active objects in the
        // the various object pools
        Map<String, TypedObjectPool<?>[]> allPools = this.objectPools.getPartitionedPools(); 
        assertNotNull(allPools);
        assertFalse(allPools.isEmpty());
        for (String name : allPools.keySet()) {
            TypedObjectPool<?> pools[] = allPools.get(name);
            TypedPoolableObjectFactory<?> factory = null;
            assertNotNull(name, pools);
            assertNotSame(0, pools.length);
            for (int i = 0; i < pools.length; i++) {
                if (pools[i] == null) continue;
                String poolName = String.format("%s-%02d", name, i);  
                factory = (TypedPoolableObjectFactory<?>)pools[i].getFactory();
                assertTrue(poolName, factory.isCountingEnabled());
                
                System.err.println(poolName + ": " + pools[i].toString());
                assertEquals(poolName, 0, pools[i].getNumActive());
            } // FOR
        } // FOR
    }
    
    /**
     * testSendClientResponse
     */
    @Test
    public void testSendClientResponse() throws Exception {
        Procedure catalog_proc = this.getProcedure(TARGET_PROCEDURE);
        PartitionSet predict_touchedPartitions = new PartitionSet(BASE_PARTITION);
        boolean predict_readOnly = true;
        boolean predict_canAbort = true;
        
        MockClientCallback callback = new MockClientCallback();
        
        LocalTransaction ts = new LocalTransaction(hstore_site);
        ts.init(1000l, EstTime.currentTimeMillis(), CLIENT_HANDLE, BASE_PARTITION,
                predict_touchedPartitions, predict_readOnly, predict_canAbort,
                catalog_proc, PARAMS, callback);
        
        ClientResponseImpl cresponse = new ClientResponseImpl(ts.getTransactionId(),
                                                              ts.getClientHandle(),
                                                              ts.getBasePartition(),
                                                              Status.OK,
                                                              HStoreConstants.EMPTY_RESULT,
                                                              "");
        hstore_site.responseSend(ts, cresponse);
        
        // Check to make sure our callback got the ClientResponse
        // And just make sure that they're the same
        assertEquals(callback, ts.getClientCallback());
        ClientResponseImpl clone = callback.getResponse();
        assertNotNull(clone);
        assertEquals(cresponse.getTransactionId(), clone.getTransactionId());
        assertEquals(cresponse.getClientHandle(), clone.getClientHandle());
    }
    
//    @Test
//    public void testSinglePartitionPassThrough() {
        // FIXME This won't work because the HStoreCoordinatorNode is now the thing that
        // actually fires off the txn in the ExecutionSite
        
//        StoreResultCallback<byte[]> done = new StoreResultCallback<byte[]>();
//        coordinator.procedureInvocation(invocation_bytes, done);
//
//        // Passed through to the mock coordinator
//        assertTrue(dtxnCoordinator.request.getLastFragment());
//        assertEquals(1, dtxnCoordinator.request.getTransactionId());
//        assertEquals(1, dtxnCoordinator.request.getFragmentCount());
//        assertEquals(0, dtxnCoordinator.request.getFragment(0).getPartitionId());
//        InitiateTaskMessage task = (InitiateTaskMessage) VoltMessage.createMessageFromBuffer(
//                dtxnCoordinator.request.getFragment(0).getWork().asReadOnlyByteBuffer(), true);
//        assertEquals(TARGET_PROCEDURE, task.getStoredProcedureName());
//        assertArrayEquals(PARAMS, task.getParameters());
//        assertEquals(null, done.getResult());
//
//        // Return results
//        Dtxn.CoordinatorResponse.Builder response = CoordinatorResponse.newBuilder();
//        response.setTransactionId(0);
//        response.setStatus(Dtxn.FragmentResponse.Status.OK);
//        byte[] output = { 0x3, 0x2, 0x1 };
//        response.addResponse(CoordinatorResponse.PartitionResponse.newBuilder()
//                .setPartitionId(0).setOutput(ByteString.copyFrom(output)));
//        dtxnCoordinator.done.run(response.build());
//        assertArrayEquals(output, done.getResult());
//    }
}
