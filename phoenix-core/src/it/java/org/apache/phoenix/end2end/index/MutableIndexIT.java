/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end.index;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import jline.internal.Log;

import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HStore;
import org.apache.hadoop.hbase.snapshot.SnapshotTestingUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.phoenix.coprocessor.UngroupedAggregateRegionObserver;
import org.apache.phoenix.end2end.ParallelStatsDisabledIT;
import org.apache.phoenix.end2end.PartialScannerResultsDisabledIT;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PTableKey;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.apache.phoenix.util.IndexScrutiny;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.TestUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.primitives.Doubles;

@RunWith(Parameterized.class)
public class MutableIndexIT extends ParallelStatsDisabledIT {
    
    protected final boolean localIndex;
    private final String tableDDLOptions;
	
    public MutableIndexIT(Boolean localIndex, String txProvider, Boolean columnEncoded) {
		this.localIndex = localIndex;
		StringBuilder optionBuilder = new StringBuilder();
		if (txProvider != null) {
			optionBuilder.append("TRANSACTIONAL=true," + PhoenixDatabaseMetaData.TRANSACTION_PROVIDER + "='" + txProvider + "'");
		}
		if (!columnEncoded) {
            if (optionBuilder.length()!=0)
                optionBuilder.append(",");
            optionBuilder.append("COLUMN_ENCODED_BYTES=0");
        }
		this.tableDDLOptions = optionBuilder.toString();
	}
    
    private static Connection getConnection(Properties props) throws SQLException {
        props.setProperty(QueryServices.INDEX_MUTATE_BATCH_SIZE_THRESHOLD_ATTRIB, Integer.toString(1));
        Connection conn = DriverManager.getConnection(getUrl(), props);
        return conn;
    }
    
    private static Connection getConnection() throws SQLException {
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        return getConnection(props);
    }
    
	@Parameters(name="MutableIndexIT_localIndex={0},transactional={1},columnEncoded={2}") // name is used by failsafe as file name in reports
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { 
                { false, null, false }, { false, null, true },
                { false, "TEPHRA", false }, { false, "TEPHRA", true },
                //{ false, "OMID", false }, { false, "OMID", true },
                { true, null, false }, { true, null, true },
                { true, "TEPHRA", false }, { true, "TEPHRA", true },
                //{ true, "OMID", false }, { true, "OMID", true },
                });
    }
    
    @Test
    public void testCoveredColumnUpdates() throws Exception {
        try (Connection conn = getConnection()) {
	        conn.setAutoCommit(false);
			String tableName = "TBL_" + generateUniqueName();
			String indexName = "IDX_" + generateUniqueName();
			String fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
			String fullIndexName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);

			TestUtil.createMultiCFTestTable(conn, fullTableName, tableDDLOptions);
            populateMultiCFTestTable(fullTableName);
            conn.createStatement().execute("CREATE " + (localIndex ? " LOCAL " : "") + " INDEX " + indexName + " ON " + fullTableName 
            		+ " (char_col1 ASC, int_col1 ASC) INCLUDE (long_col1, long_col2)");
            
            String query = "SELECT char_col1, int_col1, long_col2 from " + fullTableName;
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + query);
            if (localIndex) {
                assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + fullTableName +" [1]\nCLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));
            } else {
                assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + fullIndexName, QueryUtil.getExplainPlan(rs));
            }
            
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertEquals(3L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(3, rs.getInt(2));
            assertEquals(4L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(4, rs.getInt(2));
            assertEquals(5L, rs.getLong(3));
            assertFalse(rs.next());
            
            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + fullTableName
                    + "(varchar_pk, char_pk, int_pk, long_pk , decimal_pk, long_col2) SELECT varchar_pk, char_pk, int_pk, long_pk , decimal_pk, long_col2*2 FROM "
                    + fullTableName + " WHERE long_col2=?");
            stmt.setLong(1,4L);
            assertEquals(1,stmt.executeUpdate());
            conn.commit();

            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertEquals(3L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(3, rs.getInt(2));
            assertEquals(8L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(4, rs.getInt(2));
            assertEquals(5L, rs.getLong(3));
            assertFalse(rs.next());
            
            stmt = conn.prepareStatement("UPSERT INTO " + fullTableName
                    + "(varchar_pk, char_pk, int_pk, long_pk , decimal_pk, long_col2) SELECT varchar_pk, char_pk, int_pk, long_pk , decimal_pk, CAST(null AS BIGINT) FROM "
                    + fullTableName + " WHERE long_col2=?");
            stmt.setLong(1,3L);
            assertEquals(1,stmt.executeUpdate());
            conn.commit();
            
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertEquals(0, rs.getLong(3));
            assertTrue(rs.wasNull());
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(3, rs.getInt(2));
            assertEquals(8L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(4, rs.getInt(2));
            assertEquals(5L, rs.getLong(3));
            assertFalse(rs.next());
            if(localIndex) {
                query = "SELECT b.* from " + fullTableName + " where int_col1 = 4";
                rs = conn.createStatement().executeQuery("EXPLAIN " + query);
                assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + fullTableName +" [1]\n" +
                		"    SERVER FILTER BY TO_INTEGER(\"INT_COL1\") = 4\nCLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));
                rs = conn.createStatement().executeQuery(query);
                assertTrue(rs.next());
                assertEquals("varchar_b", rs.getString(1));
                assertEquals("charb", rs.getString(2));
                assertEquals(5, rs.getInt(3));
                assertEquals(5, rs.getLong(4));
                assertFalse(rs.next());
                
            }
        } 
    }
    
    @Test
    public void testCoveredColumns() throws Exception {
		String tableName = "TBL_" + generateUniqueName();
		String indexName = "IDX_" + generateUniqueName();
		String fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
		String fullIndexName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);
        try (Connection conn = getConnection()) {

	        conn.setAutoCommit(false);
	        String query;
	        ResultSet rs;
	        conn.createStatement().execute("CREATE TABLE " + fullTableName + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)" + tableDDLOptions);
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery(query);
	        assertFalse(rs.next());
	        
	        conn.createStatement().execute("CREATE " + (localIndex ? " LOCAL " : "") + " INDEX " + indexName + " ON " + fullTableName + " (v1) INCLUDE (v2)");
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertFalse(rs.next());
	
	        PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + " VALUES(?,?,?)");
	        stmt.setString(1,"a");
	        stmt.setString(2, "x");
	        stmt.setString(3, "1");
	        stmt.execute();
	        conn.commit();
	        
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("x",rs.getString(1));
	        assertEquals("a",rs.getString(2));
	        assertEquals("1",rs.getString(3));
	        assertFalse(rs.next());
	
	        stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + "(k,v2) VALUES(?,?)");
	        stmt.setString(1,"a");
	        stmt.setString(2, null);
	        stmt.execute();
	        conn.commit();
	        
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("x",rs.getString(1));
	        assertEquals("a",rs.getString(2));
	        assertNull(rs.getString(3));
	        assertFalse(rs.next());
	
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
	        if(localIndex) {
	            assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + fullTableName+" [1]\nCLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));            
	        } else {
	            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + fullIndexName, QueryUtil.getExplainPlan(rs));
	        }
	
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("a",rs.getString(1));
	        assertEquals("x",rs.getString(2));
	        assertNull(rs.getString(3));
	        assertFalse(rs.next());
	
	        stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + "(k,v2) VALUES(?,?)");
	        stmt.setString(1,"a");
	        stmt.setString(2,"3");
	        stmt.execute();
	        conn.commit();
	        
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
	        if(localIndex) {
	            assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + fullTableName + " [1]\nCLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));            
	        } else {
	            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + fullIndexName, QueryUtil.getExplainPlan(rs));
	        }
	        
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("a",rs.getString(1));
	        assertEquals("x",rs.getString(2));
	        assertEquals("3",rs.getString(3));
	        assertFalse(rs.next());
	
	        stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + "(k,v2) VALUES(?,?)");
	        stmt.setString(1,"a");
	        stmt.setString(2,"4");
	        stmt.execute();
	        conn.commit();
	        
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
	        if(localIndex) {
	            assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + fullTableName+" [1]\nCLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));            
	        } else {
	            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + fullIndexName, QueryUtil.getExplainPlan(rs));
	        }
	        
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("a",rs.getString(1));
	        assertEquals("x",rs.getString(2));
	        assertEquals("4",rs.getString(3));
	        assertFalse(rs.next());
        }
    }

    @Test
    public void testCompoundIndexKey() throws Exception {
		String tableName = "TBL_" + generateUniqueName();
		String indexName = "IDX_" + generateUniqueName();
		String fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
		String fullIndexName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);
        try (Connection conn = getConnection()) {
	        conn.setAutoCommit(false);
	        String query;
	        ResultSet rs;
	        // make sure that the tables are empty, but reachable
	        conn.createStatement().execute("CREATE TABLE " + fullTableName + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)" + tableDDLOptions);
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery(query);
	        assertFalse(rs.next());
	        conn.createStatement().execute("CREATE " + (localIndex ? " LOCAL " : "") + " INDEX " + indexName + " ON " + fullTableName + " (v1, v2)");
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertFalse(rs.next());
	
	        // load some data into the table
	        PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + " VALUES(?,?,?)");
	        stmt.setString(1,"a");
	        stmt.setString(2, "x");
	        stmt.setString(3, "1");
	        stmt.execute();
	        conn.commit();
	        
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("x",rs.getString(1));
	        assertEquals("1",rs.getString(2));
	        assertEquals("a",rs.getString(3));
	        assertFalse(rs.next());
	
	        stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + " VALUES(?,?,?)");
	        stmt.setString(1,"a");
	        stmt.setString(2, "y");
	        stmt.setString(3, null);
	        stmt.execute();
	        conn.commit();
	        
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("y",rs.getString(1));
	        assertNull(rs.getString(2));
	        assertEquals("a",rs.getString(3));
	        assertFalse(rs.next());
	
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
	        if (localIndex) {
	            assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + fullTableName+" [1]\n"
	                    + "    SERVER FILTER BY FIRST KEY ONLY\n"
	                    + "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));
	        } else {
	            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + fullIndexName + "\n"
	                       + "    SERVER FILTER BY FIRST KEY ONLY", QueryUtil.getExplainPlan(rs));
	        }
	        //make sure the data table looks like what we expect
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("a",rs.getString(1));
	        assertEquals("y",rs.getString(2));
	        assertNull(rs.getString(3));
	        assertFalse(rs.next());
	        
	        // Upsert new row with null leading index column
	        stmt.setString(1,"b");
	        stmt.setString(2, null);
	        stmt.setString(3, "3");
	        stmt.execute();
	        conn.commit();
	        
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals(null,rs.getString(1));
	        assertEquals("3",rs.getString(2));
	        assertEquals("b",rs.getString(3));
	        assertTrue(rs.next());
	        assertEquals("y",rs.getString(1));
	        assertNull(rs.getString(2));
	        assertEquals("a",rs.getString(3));
	        assertFalse(rs.next());
	
	        // Update row with null leading index column to have a value
	        stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + " VALUES(?,?)");
	        stmt.setString(1,"b");
	        stmt.setString(2, "z");
	        stmt.execute();
	        conn.commit();
	        
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("y",rs.getString(1));
	        assertNull(rs.getString(2));
	        assertEquals("a",rs.getString(3));
	        assertTrue(rs.next());
	        assertEquals("z",rs.getString(1));
	        assertEquals("3",rs.getString(2));
	        assertEquals("b",rs.getString(3));
	        assertFalse(rs.next());
        }

    }
    
    /**
     * There was a case where if there were multiple updates to a single row in the same batch, the
     * index wouldn't be updated correctly as each element of the batch was evaluated with the state
     * previous to the batch, rather than with the rest of the batch. This meant you could do a put
     * and a delete on a row in the same batch and the index result would contain the current + put
     * and current + delete, but not current + put + delete.
     * @throws Exception on failure
     */
    @Test
    public void testMultipleUpdatesToSingleRow() throws Exception {
		String tableName = "TBL_" + generateUniqueName();
		String indexName = "IDX_" + generateUniqueName();
		String fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
		String fullIndexName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);
        try (Connection conn = getConnection()) {
	        conn.setAutoCommit(false);
	        String query;
	        ResultSet rs;
	        // make sure that the tables are empty, but reachable
	        conn.createStatement().execute(
	          "CREATE TABLE " + fullTableName
	              + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)" + tableDDLOptions);
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery(query);
	        assertFalse(rs.next());
	
	        conn.createStatement().execute("CREATE " + (localIndex ? " LOCAL " : "") + " INDEX " + indexName + " ON " + fullTableName + " (v1, v2)");
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertFalse(rs.next());
	    
	        // load some data into the table
	        PreparedStatement stmt =
	            conn.prepareStatement("UPSERT INTO " + fullTableName + " VALUES(?,?,?)");
	        stmt.setString(1, "a");
	        stmt.setString(2, "x");
	        stmt.setString(3, "1");
	        stmt.execute();
	        conn.commit();
	        
	        // make sure the index is working as expected
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("x", rs.getString(1));
	        assertEquals("1", rs.getString(2));
	        assertEquals("a", rs.getString(3));
	        assertFalse(rs.next());
	      
	        // do multiple updates to the same row, in the same batch
	        stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + "(k, v1) VALUES(?,?)");
	        stmt.setString(1, "a");
	        stmt.setString(2, "y");
	        stmt.execute();
	        stmt = conn.prepareStatement("UPSERT INTO " + fullTableName + "(k,v2) VALUES(?,?)");
	        stmt.setString(1, "a");
	        stmt.setString(2, null);
	        stmt.execute();
	        conn.commit();
	    
	        query = "SELECT * FROM " + fullIndexName;
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("y", rs.getString(1));
	        assertNull(rs.getString(2));
	        assertEquals("a", rs.getString(3));
	        assertFalse(rs.next());
	    
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
	        if(localIndex) {
	            assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER " + fullTableName+" [1]\n"
	                    + "    SERVER FILTER BY FIRST KEY ONLY\n"
	                    + "CLIENT MERGE SORT",
	                QueryUtil.getExplainPlan(rs));
	        } else {
	            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + fullIndexName + "\n"
	                    + "    SERVER FILTER BY FIRST KEY ONLY",
	                QueryUtil.getExplainPlan(rs));
	        }
	    
	        // check that the data table matches as expected
	        rs = conn.createStatement().executeQuery(query);
	        assertTrue(rs.next());
	        assertEquals("a", rs.getString(1));
	        assertEquals("y", rs.getString(2));
	        assertNull(rs.getString(3));
	        assertFalse(rs.next());
        }
    }
    
    @Test
    public void testUpsertingNullForIndexedColumns() throws Exception {
		String tableName = "TBL_" + generateUniqueName();
		String indexName = "IDX_" + generateUniqueName();
		String fullIndexName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);
        String testTableName = tableName + "_" + System.currentTimeMillis();
        try (Connection conn = getConnection()) {
	        conn.setAutoCommit(false);
	        ResultSet rs;
    		Statement stmt = conn.createStatement();
    		stmt.execute("CREATE TABLE " + testTableName + "(v1 VARCHAR PRIMARY KEY, v2 DOUBLE, v3 VARCHAR) "+tableDDLOptions);
    		stmt.execute("CREATE " + (localIndex ? "LOCAL" : "") + " INDEX " + indexName + " ON " + testTableName + "  (v2) INCLUDE(v3)");
    		
    		//create a row with value null for indexed column v2
    		stmt.executeUpdate("upsert into " + testTableName + " values('cc1', null, 'abc')");
    		conn.commit();
    		
    		//assert values in index table 
    		rs = stmt.executeQuery("select * from " + fullIndexName);
    		assertTrue(rs.next());
    		assertEquals(0, Doubles.compare(0, rs.getDouble(1)));
    		assertTrue(rs.wasNull());
    		assertEquals("cc1", rs.getString(2));
    		assertEquals("abc", rs.getString(3));
    		assertFalse(rs.next());
    		
    		//assert values in data table
    		rs = stmt.executeQuery("select v1, v2, v3 from " + testTableName);
    		assertTrue(rs.next());
    		assertEquals("cc1", rs.getString(1));
    		assertEquals(0, Doubles.compare(0, rs.getDouble(2)));
    		assertTrue(rs.wasNull());
    		assertEquals("abc", rs.getString(3));
    		assertFalse(rs.next());
    		
    		//update the previously null value for indexed column v2 to a non-null value 1.23
    		stmt.executeUpdate("upsert into " + testTableName + " values('cc1', 1.23, 'abc')");
    		conn.commit();
    		
    		//assert values in data table
    		rs = stmt.executeQuery("select /*+ NO_INDEX */ v1, v2, v3 from " + testTableName);
    		assertTrue(rs.next());
    		assertEquals("cc1", rs.getString(1));
    		assertEquals(0, Doubles.compare(1.23, rs.getDouble(2)));
    		assertEquals("abc", rs.getString(3));
    		assertFalse(rs.next());
    		
    		//assert values in index table 
    		rs = stmt.executeQuery("select * from " + indexName);
    		assertTrue(rs.next());
    		assertEquals(0, Doubles.compare(1.23, rs.getDouble(1)));
    		assertEquals("cc1", rs.getString(2));
    		assertEquals("abc", rs.getString(3));
    		assertFalse(rs.next());
    		
    		//update the value for indexed column v2 back to null
    		stmt.executeUpdate("upsert into " + testTableName + " values('cc1', null, 'abc')");
    		conn.commit();
    		
    		//assert values in index table 
    		rs = stmt.executeQuery("select * from " + indexName);
    		assertTrue(rs.next());
    		assertEquals(0, Doubles.compare(0, rs.getDouble(1)));
    		assertTrue(rs.wasNull());
    		assertEquals("cc1", rs.getString(2));
    		assertEquals("abc", rs.getString(3));
    		assertFalse(rs.next());
    		
    		//assert values in data table
    		rs = stmt.executeQuery("select v1, v2, v3 from " + testTableName);
    		assertTrue(rs.next());
    		assertEquals("cc1", rs.getString(1));
    		assertEquals(0, Doubles.compare(0, rs.getDouble(2)));
    		assertEquals("abc", rs.getString(3));
    		assertFalse(rs.next());
    	} 
    }
    
	
    private void assertImmutableRows(Connection conn, String fullTableName, boolean expectedValue) throws SQLException {
        PhoenixConnection pconn = conn.unwrap(PhoenixConnection.class);
        assertEquals(expectedValue, pconn.getTable(new PTableKey(pconn.getTenantId(), fullTableName)).isImmutableRows());
    }
    
    @Test
    public void testAlterTableWithImmutability() throws Exception {
        String query;
        ResultSet rs;
		String tableName = "TBL_" + generateUniqueName();
		String fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);

        try (Connection conn = getConnection()) {
	        conn.setAutoCommit(false);
	        conn.createStatement().execute(
	            "CREATE TABLE " + fullTableName +" (k VARCHAR NOT NULL PRIMARY KEY, v VARCHAR) " + tableDDLOptions);
	        
	        query = "SELECT * FROM " + fullTableName;
	        rs = conn.createStatement().executeQuery(query);
	        assertFalse(rs.next());
	
	        assertImmutableRows(conn,fullTableName, false);
	        conn.createStatement().execute("ALTER TABLE " + fullTableName +" SET IMMUTABLE_ROWS=true");
	        assertImmutableRows(conn,fullTableName, true);
	        
	        
	        conn.createStatement().execute("ALTER TABLE " + fullTableName +" SET immutable_rows=false");
	        assertImmutableRows(conn,fullTableName, false);
        }
    }

    @Test
    public void testIndexHalfStoreFileReader() throws Exception {
        Connection conn1 = getConnection();
        ConnectionQueryServices connectionQueryServices = driver.getConnectionQueryServices(getUrl(), TestUtil.TEST_PROPERTIES);
		HBaseAdmin admin = connectionQueryServices.getAdmin();
		String tableName = "TBL_" + generateUniqueName();
		String indexName = "IDX_" + generateUniqueName();
        createBaseTable(conn1, tableName, "('e')");
        conn1.createStatement().execute("CREATE "+(localIndex?"LOCAL":"")+" INDEX " + indexName + " ON " + tableName + "(v1)" + (localIndex?"":" SPLIT ON ('e')"));
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('b',1,2,4,'z')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('f',1,2,3,'z')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('j',2,4,2,'a')");
        conn1.createStatement().execute("UPSERT INTO "+tableName+" values('q',3,1,1,'c')");
        conn1.commit();
        

        String query = "SELECT count(*) FROM " + tableName +" where v1<='z'";
        ResultSet rs = conn1.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals(4, rs.getInt(1));

        TableName indexTable = TableName.valueOf(localIndex ? tableName : indexName);
        admin.flush(indexTable.getNameAsString());
        boolean merged = false;
        HTableInterface table = connectionQueryServices.getTable(indexTable.getName());
        // merge regions until 1 left
        long numRegions = 0;
        while (true) {
            rs = conn1.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals(4, rs.getInt(1)); //TODO this returns 5 sometimes instead of 4, duplicate results?
            try {
                List<HRegionInfo> indexRegions = admin.getTableRegions(indexTable);
                numRegions = indexRegions.size();
                if (numRegions==1) {
                    break;
                }
                if(!merged) {
                    List<HRegionInfo> regions =
                            admin.getTableRegions(indexTable);
                    Log.info("Merging: " + regions.size());
                    admin.mergeRegions(regions.get(0).getEncodedNameAsBytes(),
                            regions.get(1).getEncodedNameAsBytes(), false);
                    merged = true;
                    Threads.sleep(10000);
                }
            } catch (Exception ex) {
                Log.info(ex);
            }
            long waitStartTime = System.currentTimeMillis();
            // wait until merge happened
            while (System.currentTimeMillis() - waitStartTime < 10000) {
                List<HRegionInfo> regions = admin.getTableRegions(indexTable);
                Log.info("Waiting:" + regions.size());
                if (regions.size() < numRegions) {
                    break;
                }
                Threads.sleep(1000);
            }
            SnapshotTestingUtils.waitForTableToBeOnline(BaseTest.getUtility(), indexTable);
            assertTrue("Index table should be online ", admin.isTableAvailable(indexTable));
        }
        long waitStartTime = System.currentTimeMillis();
        // wait until merge happened
        while (System.currentTimeMillis() - waitStartTime < 10000) {
            List<HRegionInfo> regions = admin.getTableRegions(indexTable);
            Log.info("Waiting:" + regions.size());
            if (regions.size() < numRegions) {
                break;
            }
            Threads.sleep(1000);
        }
        SnapshotTestingUtils.waitForTableToBeOnline(BaseTest.getUtility(), indexTable);
        assertTrue("Index table should be online ", admin.isTableAvailable(indexTable));
    }

    private void createBaseTable(Connection conn, String tableName, String splits) throws SQLException {
        String ddl = "CREATE TABLE " + tableName + " (t_id VARCHAR NOT NULL,\n" +
                "k1 INTEGER NOT NULL,\n" +
                "k2 INTEGER NOT NULL,\n" +
                "k3 INTEGER,\n" +
                "v1 VARCHAR,\n" +
                "CONSTRAINT pk PRIMARY KEY (t_id, k1, k2))\n"
                        + (tableDDLOptions!=null?tableDDLOptions:"") + (splits != null ? (" split on " + splits) : "");
        conn.createStatement().execute(ddl);
    }
    
  @Test
  public void testTenantSpecificConnection() throws Exception {
	  String tableName = "TBL_" + generateUniqueName();
	  String indexName = "IDX_" + generateUniqueName();
	  String fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
	  Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
      try (Connection conn = getConnection()) {
          conn.setAutoCommit(false);
          // create data table
          conn.createStatement().execute(
              "CREATE TABLE IF NOT EXISTS " + fullTableName + 
              "(TENANT_ID CHAR(15) NOT NULL,"+
              "TYPE VARCHAR(25),"+
              "ENTITY_ID CHAR(15) NOT NULL,"+
              "CONSTRAINT PK_CONSTRAINT PRIMARY KEY (TENANT_ID, ENTITY_ID)) MULTI_TENANT=TRUE "
              + (!tableDDLOptions.isEmpty() ? "," + tableDDLOptions : "") );
          // create index
          conn.createStatement().execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + fullTableName + " (ENTITY_ID, TYPE)");
          
          // upsert rows
          String dml = "UPSERT INTO " + fullTableName + " (ENTITY_ID, TYPE) VALUES ( ?, ?)";
          props.setProperty(PhoenixRuntime.TENANT_ID_ATTRIB, "tenant1");
          // connection is tenant-specific
          try (Connection tenantConn = getConnection(props)) {
              // upsert one row
              upsertRow(dml, tenantConn, 0);
              tenantConn.commit();
              ResultSet rs = tenantConn.createStatement().executeQuery("SELECT ENTITY_ID FROM " + fullTableName + " ORDER BY TYPE LIMIT 5");
              assertTrue(rs.next());
              // upsert two rows which ends up using the tenant cache
              upsertRow(dml, tenantConn, 1);
              upsertRow(dml, tenantConn, 2);
              tenantConn.commit();
          }
      }
  }

  // Tests that if major compaction is run on a table with a disabled index,
  // deleted cells are kept
  @Test
  public void testCompactDisabledIndex() throws Exception {
      try (Connection conn = getConnection()) {
          String schemaName = generateUniqueName();
          String dataTableName = generateUniqueName() + "_DATA";
          String dataTableFullName = SchemaUtil.getTableName(schemaName, dataTableName);
          String indexTableName = generateUniqueName() + "_IDX";
          String indexTableFullName = SchemaUtil.getTableName(schemaName, indexTableName);
          conn.createStatement().execute(
              String.format(PartialScannerResultsDisabledIT.TEST_TABLE_DDL, dataTableFullName));
          conn.createStatement().execute(String.format(PartialScannerResultsDisabledIT.INDEX_1_DDL,
              indexTableName, dataTableFullName));

          //insert a row, and delete it
          PartialScannerResultsDisabledIT.writeSingleBatch(conn, 1, 1, dataTableFullName);
          conn.createStatement().execute("DELETE FROM " + dataTableFullName);
          conn.commit();

          // disable the index, simulating an index write failure
          PhoenixConnection pConn = conn.unwrap(PhoenixConnection.class);
          IndexUtil.updateIndexState(pConn, indexTableFullName, PIndexState.DISABLE,
              EnvironmentEdgeManager.currentTimeMillis());

          // major compaction should not remove the deleted row
          List<HRegion> regions = getUtility().getHBaseCluster().getRegions(TableName.valueOf(dataTableFullName));
          HRegion hRegion = regions.get(0);
          hRegion.flushcache();
          HStore store = (HStore) hRegion.getStore(QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES);
          store.triggerMajorCompaction();
          store.compactRecentForTestingAssumingDefaultPolicy(1);
          HTableInterface dataHTI = conn.unwrap(PhoenixConnection.class).getQueryServices().getTable(Bytes.toBytes(dataTableFullName));
          assertEquals(1, TestUtil.getRawRowCount(dataHTI));

          // reenable the index
          IndexUtil.updateIndexState(pConn, indexTableFullName, PIndexState.INACTIVE,
              EnvironmentEdgeManager.currentTimeMillis());
          IndexUtil.updateIndexState(pConn, indexTableFullName, PIndexState.ACTIVE, 0L);

          // now major compaction should remove the deleted row
          store.triggerMajorCompaction();
          store.compactRecentForTestingAssumingDefaultPolicy(1);
          dataHTI = conn.unwrap(PhoenixConnection.class).getQueryServices().getTable(Bytes.toBytes(dataTableFullName));
          assertEquals(0, TestUtil.getRawRowCount(dataHTI));
      }
  }

  // some tables (e.g. indexes on views) have UngroupedAgg coproc loaded, but don't have a
  // corresponding row in syscat.  This tests that compaction isn't blocked
  @Test(timeout=120000)
  public void testCompactNonPhoenixTable() throws Exception {
      try (Connection conn = getConnection()) {
          // create a vanilla HBase table (non-Phoenix)
          String randomTable = generateUniqueName();
          TableName hbaseTN = TableName.valueOf(randomTable);
          byte[] famBytes = Bytes.toBytes("fam");
          HTable hTable = getUtility().createTable(hbaseTN, famBytes);
          Put put = new Put(Bytes.toBytes("row"));
          byte[] value = new byte[1];
          Bytes.random(value);
          put.add(famBytes, Bytes.toBytes("colQ"), value);
          hTable.put(put);

          TestUtil.addCoprocessor(conn, randomTable, UngroupedAggregateRegionObserver.class);

          // major compaction shouldn't cause a timeout or RS abort
          List<HRegion> regions = getUtility().getHBaseCluster().getRegions(hbaseTN);
          HRegion hRegion = regions.get(0);
          hRegion.flushcache();
          HStore store = (HStore) hRegion.getStore(famBytes);
          store.triggerMajorCompaction();
          store.compactRecentForTestingAssumingDefaultPolicy(1);

          // we should be able to compact syscat itself as well
          regions = getUtility().getHBaseCluster().getRegions(TableName.valueOf(PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME));
          hRegion = regions.get(0);
          hRegion.flushcache();
          store = (HStore) hRegion.getStore(QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES);
          store.triggerMajorCompaction();
          store.compactRecentForTestingAssumingDefaultPolicy(1);
      }
  }

  /**
   * PHOENIX-4988
   * Test updating only a non-indexed column after two successive deletes to an indexed row
   */
  @Test
  public void testUpdateNonIndexedColumn() throws Exception {
      String tableName = "TBL_" + generateUniqueName();
      String indexName = "IDX_" + generateUniqueName();
      String fullTableName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, tableName);
      String fullIndexName = SchemaUtil.getTableName(TestUtil.DEFAULT_SCHEMA_NAME, indexName);
      try (Connection conn = getConnection()) {
          conn.setAutoCommit(false);
          conn.createStatement().execute("CREATE TABLE " + fullTableName + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR) " + tableDDLOptions);
          conn.createStatement().execute("CREATE " + (localIndex ? " LOCAL " : "") + " INDEX " + indexName + " ON " + fullTableName + " (v2)");
          conn.createStatement().executeUpdate("UPSERT INTO " + fullTableName + "(k,v1,v2) VALUES ('testKey','v1_1','v2_1')");
          conn.commit();
          conn.createStatement().executeUpdate("DELETE FROM " + fullTableName);
          conn.commit();
          conn.createStatement().executeUpdate("UPSERT INTO " + fullTableName + "(k,v1,v2) VALUES ('testKey','v1_2','v2_2')");
          conn.commit();
          conn.createStatement().executeUpdate("DELETE FROM " + fullTableName);
          conn.commit();
          conn.createStatement().executeUpdate("UPSERT INTO " + fullTableName + "(k,v1) VALUES ('testKey','v1_3')");
          conn.commit();
          IndexScrutiny.scrutinizeIndex(conn, fullTableName, fullIndexName);
          // PHOENIX-4980
          // When there is a flush after a data table update of non-indexed columns, the
          // index gets out of sync on the next write
          getUtility().getHBaseAdmin().flush(TableName.valueOf(fullTableName));
          conn.createStatement().executeUpdate("UPSERT INTO " + fullTableName + "(k,v1,v2) VALUES ('testKey','v1_4','v2_3')");
          conn.commit();
          IndexScrutiny.scrutinizeIndex(conn, fullTableName, fullIndexName);
      }
  }

private void upsertRow(String dml, Connection tenantConn, int i) throws SQLException {
    PreparedStatement stmt = tenantConn.prepareStatement(dml);
      stmt.setString(1, "00000000000000" + String.valueOf(i));
      stmt.setString(2, String.valueOf(i));
      assertEquals(1,stmt.executeUpdate());
}
}
