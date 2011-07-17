/*-
 * See the file LICENSE for redistribution information.
 * 
 * Copyright (c) 2010, 2011 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package repmgrtests;


import java.io.File;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

import com.sleepycat.db.Database;
import com.sleepycat.db.DatabaseConfig;
import com.sleepycat.db.DatabaseEntry;
import com.sleepycat.db.DatabaseType;
import com.sleepycat.db.Environment;
import com.sleepycat.db.EnvironmentConfig;
import com.sleepycat.db.EventHandlerAdapter;
import com.sleepycat.db.ReplicationHostAddress;
import com.sleepycat.db.ReplicationManagerStartPolicy;
import com.sleepycat.db.ReplicationTimeoutType;
import com.sleepycat.db.VerboseConfig;

/**
 * Regression test for #15927.  If no clients running, master should
 * not wait for acks.  If acks are required according to the policy,
 * master should return failure immediately.
 */
public class TestNoClient {
    private static final String TEST_DIR_NAME = "TESTDIR";
    private File testdir;
    class MyEventHandler extends EventHandlerAdapter {
        private int permFailures;
        
        @Override synchronized public void handleRepPermFailedEvent() {
            permFailures++;
        }

        synchronized int getPermFailureCount() { return permFailures; }
    }
    
    @Before public void setUp() throws Exception {
        testdir = new File(TEST_DIR_NAME);
        Util.rm_rf(testdir);
        testdir.mkdir();
    }
    
    @Test public void timeTxns() throws Exception {
        doIt(false);
    }
    
    @Test public void timePeer() throws Exception {
        doIt(true);
    }

    private void doIt(boolean peer) throws Exception {
        int[] testPorts = Util.findAvailablePorts(2);
        int masterPort = testPorts[0];
        int clientPort = testPorts[1];
        
        EnvironmentConfig ec = makeBasicConfig();
        MyEventHandler mon = new MyEventHandler();
        ec.setEventHandler(mon);
        ec.setReplicationManagerLocalSite(new ReplicationHostAddress("localhost", masterPort));
        ec.replicationManagerAddRemoteSite(new ReplicationHostAddress("localhost", clientPort), peer);
        Environment master = new Environment(mkdir("master"), ec);
        master.replicationManagerStart(1, ReplicationManagerStartPolicy.REP_MASTER);
        
        DatabaseConfig dc = new DatabaseConfig();
        dc.setTransactional(true);
        dc.setAllowCreate(true);
        dc.setType(DatabaseType.BTREE);
        Database db = master.openDatabase(null, "test.db", null, dc);
        
        // Make the Ack timeout huge (10 seconds), to make it very
        // easy to discern whether the master waited.
        // 
        master.setReplicationTimeout(ReplicationTimeoutType.ACK_TIMEOUT,
                                     10000000);
        int initialFailureCount = mon.getPermFailureCount();
        long startTime = System.currentTimeMillis();
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry value = new DatabaseEntry();
        key.setData("msg".getBytes());
        value.setData("hello, world!".getBytes());
        db.put(null, key, value);

        assertEquals(initialFailureCount + 1, mon.getPermFailureCount());

        // Note that Java measures time in milliseconds, although DB
        // required us to specify microseconds (above).
        // 
        long duration = System.currentTimeMillis() - startTime;
        assertTrue(duration < 5000);

        db.close();
        master.close();
    }
    
    public static EnvironmentConfig makeBasicConfig() {
        EnvironmentConfig ec = new EnvironmentConfig();
        ec.setAllowCreate(true);
        ec.setInitializeCache(true);
        ec.setInitializeLocking(true);
        ec.setInitializeLogging(true);
        ec.setInitializeReplication(true);
        ec.setTransactional(true);
        ec.setThreaded(true);
        ec.setReplicationNumSites(2);
        if (Boolean.getBoolean("VERB_REPLICATION"))
            ec.setVerbose(VerboseConfig.REPLICATION, true);
        return (ec);
    }
    
    public File mkdir(String dname) {
        File f = new File(testdir, dname);
        f.mkdir();
        return f;
    }
}
