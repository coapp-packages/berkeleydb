/*-
 * See the file LICENSE for redistribution information.
 * 
 * Copyright (c) 2010, 2011 Oracle and/or its affiliates.  All rights reserved.
 *
 */

package repmgrtests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.db.BtreeStats;
import com.sleepycat.db.Database;
import com.sleepycat.db.DatabaseConfig;
import com.sleepycat.db.DatabaseEntry;
import com.sleepycat.db.DatabaseType;
import com.sleepycat.db.Environment;
import com.sleepycat.db.EnvironmentConfig;
import com.sleepycat.db.EventHandlerAdapter;
import com.sleepycat.db.LogSequenceNumber;
import com.sleepycat.db.ReplicationConfig;
import com.sleepycat.db.ReplicationHostAddress;
import com.sleepycat.db.ReplicationManagerSiteInfo;
import com.sleepycat.db.ReplicationManagerStartPolicy;
import com.sleepycat.db.ReplicationTransport;
import com.sleepycat.db.VerboseConfig;

/**
 * Basic test of handling full output queue.  Use fiddler to simulate
 * a clogged client during internal init.  With a completely clogged
 * output queue, make sure we don't block live txn threads, but that
 * we do count a perm failure since we don't get an ack (since of
 * course the client didn't even receive our message.)
 */
public class TestDrainCommitx {
    private static final String TEST_DIR_NAME = "TESTDIR";

    private File testdir;
    private byte[] data;
    private Process fiddler;
    private int masterPort;
    private int clientPort;
    private int masterSpoofPort;
    private int clientSpoofPort;
    private int mgrPort;

    class MyEventHandler extends EventHandlerAdapter {
        private boolean done = false;
        private boolean panic = false;
        private int permFailCount = 0;
        private int newmasterCount = 0;
		
        @Override
            synchronized public void handleRepStartupDoneEvent() {
                done = true;
                notifyAll();
            }

        @Override
            synchronized public void handleRepPermFailedEvent() {
                permFailCount++;
            }

        synchronized public int getPermFailCount() { return permFailCount; }
		
        @Override
            synchronized public void handlePanicEvent() {
                done = true;
                panic = true;
                notifyAll();
            }

        @Override synchronized public void handleRepNewMasterEvent(int eid) {
            newmasterCount++;
            notifyAll();
        }

        synchronized public int getNewmasterCount() {
            return newmasterCount;
        }

        synchronized void awaitNewmasterCountExceeds(int count, long deadline)
            throws Exception
        {
            long now;

            while (newmasterCount <= count) {
                if ((now = System.currentTimeMillis()) >= deadline)
                    throw new Exception("aborted by timeout");
                long waitTime = deadline-now;
                wait(waitTime);
                if (panic)
                    throw new Exception("aborted by panic in DB");
            }
        }
		
        synchronized void await() throws Exception {
            while (!done) { wait(); }
            if (panic)
                throw new Exception("aborted by panic in DB");
        }
    }

    @Before
	public void setUp() throws Exception {
        testdir = new File(TEST_DIR_NAME);
        Util.rm_rf(testdir);
        testdir.mkdir();

        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStreamWriter w = new OutputStreamWriter(baos);
        for (int i=0; i<100; i++) { w.write(alphabet); }
        w.close();
        data = baos.toByteArray();

        
        if (Boolean.getBoolean("MANUAL_FIDDLER_START")) {
            masterPort = 6000;
            clientPort = 6001;
            masterSpoofPort = 7000;
            clientSpoofPort = 7001;
            mgrPort = 8000;
            fiddler = null;
        } else {
            PortsConfig p = new PortsConfig(2);
            masterPort = p.getRealPort(0);
            masterSpoofPort = p.getSpoofPort(0);
            clientPort = p.getRealPort(1);
            clientSpoofPort = p.getSpoofPort(1);
            mgrPort = p.getManagerPort();
            System.out.println("setUp: " + mgrPort + "/" + p.getFiddlerConfig());
            fiddler = Util.startFiddler(p, getClass().getName());
        }
    }

    // ### TODO: share this clean-up technique with all relevant tests
    // 
    @After public void tearDown() throws Exception {
        if (fiddler != null) { fiddler.destroy(); }
    }
	
    @Test public void testDraining() throws Exception {
        // TODO: move this to a test runner.
        if (Boolean.getBoolean("debug.pause")) {
            // force DB to be loaded first (is this necessary?)
            Environment.getVersionString();
            System.out.println(".> ");
            System.in.read();
        }

        EnvironmentConfig masterConfig = makeBasicConfig();
//         masterConfig.setMessageHandler(new MessageHandler() {
//                 public void message(Environment e, String msg) {
//                     if (msg.indexOf("queued") >= 0)
//                         System.out.println("we got this: " + msg);
//                 }
//             });
        masterConfig.setReplicationLimit(100000000);
        masterConfig.setReplicationManagerLocalSite(new ReplicationHostAddress("localhost", masterPort));
        MyEventHandler masterMonitor = new MyEventHandler();
        masterConfig.setEventHandler(masterMonitor);
        File masterDir = mkdir("master");
        Environment master = new Environment(masterDir, masterConfig);
        master.replicationManagerStart(3, ReplicationManagerStartPolicy.REP_MASTER);
		
        DatabaseConfig dc = new DatabaseConfig(); 
       dc.setTransactional(true);
        dc.setAllowCreate(true);
        dc.setType(DatabaseType.BTREE);
        dc.setPageSize(4096);
        Database db = master.openDatabase(null, "test.db", null, dc);
		
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry value = new DatabaseEntry();
        value.setData(data);
        for (int i=0; i<120; i++) {
            String k = "The record number is: " + i;
            key.setData(k.getBytes());
            db.put(null, key, value);
        }

        BtreeStats stats = (BtreeStats)db.getStats(null, null);
        assertTrue(stats.getPageCount() >= 50);
        db.close();


        // create client, but don't sync yet.  Wait a moment so that
        // the connection gets established.  Once it is (I should
        // probably have polled repmgr-stats for this, rather than the
        // stupid, slow 2 second assumption), we can send the fiddler
        // command.
        //    This is a workaround for what we really wanted to do,
        // which was to use regular immediate sync, telling the
        // fiddler in advance (of having established the connection)
        // what we wanted to do.  But fiddler can't handle that at the
        // moment.
        // 
        EnvironmentConfig ec = makeBasicConfig();
        ReplicationHostAddress clientAddr =
            new ReplicationHostAddress("localhost", clientPort);
        ec.setReplicationManagerLocalSite(clientAddr);
        ec.replicationManagerAddRemoteSite(new ReplicationHostAddress("localhost", masterSpoofPort), false);
        MyEventHandler clientMonitor = new MyEventHandler();
        ec.setEventHandler(clientMonitor);
        Environment client = new Environment(mkdir("client"), ec);
        client.setReplicationConfig(ReplicationConfig.DELAYCLIENT, true);
        client.replicationManagerStart(1, ReplicationManagerStartPolicy.REP_CLIENT);
        
        for (long deadline = System.currentTimeMillis() + 2000;;Thread.sleep(100)) {
            if (System.currentTimeMillis() > deadline)
                throw new Exception("what's taking so long to connect?");
            ReplicationManagerSiteInfo[] connections = master.getReplicationManagerSiteList();
            if (connections.length == 1 && connections[0].isConnected()) {
                assertEquals("localhost", connections[0].addr.host);
                assertEquals(clientSpoofPort, connections[0].addr.port);
                break;
            }
        }

        // tell fiddler to stop reading once it sees a PAGE message
        // 
        Socket s = new Socket("localhost", mgrPort);
        OutputStreamWriter w = new OutputStreamWriter(s.getOutputStream());

        String path1 = "{" + masterPort + "," + clientPort + "}"; // looks like {6000,6001}
        w.write("{" + path1 + ",page_clog}\r\n");
        w.flush();
        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
        assertEquals("ok", br.readLine());

        clientMonitor.awaitNewmasterCountExceeds(0, System.currentTimeMillis() + 2000);
        client.syncReplication();

        // wait til it gets stuck
        Thread.sleep(5000);     // FIXME


        // do another new live transaction at the master, make sure
        // that can work.  Instead of writing into an existing
        // database, create a new one.  That avoid a potential
        // conflict with internal init when we close it (#15820).
        // 
        int initialCount = masterMonitor.getPermFailCount();
        long start = System.currentTimeMillis();
        db = master.openDatabase(null, "dummy.db", null, dc);
        long duration = System.currentTimeMillis() - start;
        assertEquals(initialCount+1, masterMonitor.getPermFailCount());

        // In this case, the master should be able to realize that
        // there's no hope of an ack, so it shouldn't wait for the
        // timeout.  But currently it does (bug #15827), so skip this
        // part of the test.
        // 
//         assertTrue("duration: " + duration, duration < 1000);
        db.close(false);

        // TODO: check timing too.
		

        // Hey, this is good.  I get for free a test of releasing the
        // thread on db_rep->finished; right?  Make sure it doesn't
        // take too long (is this too undependable?).
        //
        start = System.currentTimeMillis();
        master.close();
        duration = System.currentTimeMillis() - start;
        assertTrue("duration: " + duration, duration < 1000);

        // At this point, the client is wedged, stuck in internal
        // init.  It might be nice to clean it up, but doing so is a
        // bit difficult, and since we're really done with this test
        // anyway it's not crucial.

        w.write("shutdown\r\n");
        w.flush();
        assertEquals("ok", br.readLine());
        s.close();
        fiddler = null;
    }

    static class RepMessage { DatabaseEntry control, rec; }
        
    static class MyTransport implements ReplicationTransport {
        private List<RepMessage> msgs = new ArrayList<RepMessage>();
        RepMessage[] getMessages() {
            return msgs.toArray(new RepMessage[0]);
        }
        public int send(Environment env, DatabaseEntry control, DatabaseEntry rec,
                        LogSequenceNumber lsn, int eid, boolean noBuffer,
                        boolean perm, boolean anywhere, boolean isRetry)
        {
            RepMessage msg = new RepMessage();
            msg.control = control;
            msg.rec = rec;
            msgs.add(msg);

            return 0;
        }
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
        ec.setReplicationNumSites(3);
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
