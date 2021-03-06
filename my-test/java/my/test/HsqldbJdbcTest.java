/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package my.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Assert;

public class HsqldbJdbcTest {

    public static void main(String[] args) throws Exception {
        // try {
        // Class.forName("org.hsqldb.jdbc.JDBCDriver");
        // } catch (Exception e) {
        // System.err.println("ERROR: failed to load HSQLDB JDBC driver.");
        // e.printStackTrace();
        // return;
        // }
        String url = "jdbc:hsqldb:hsql://localhost/test;ifexists=false";
        url = "jdbc:hsqldb:hsql://localhost/";
        Connection conn = DriverManager.getConnection(url, "SA", "");
        Statement stmt = conn.createStatement();
        // SET DATABASE TRANSACTION CONTROL { LOCKS | MVLOCKS | MVCC }
        stmt.executeUpdate("SET DATABASE TRANSACTION CONTROL MVLOCKS");
        stmt.executeUpdate("DROP TABLE IF EXISTS test");
        stmt.executeUpdate("CREATE CACHED TABLE IF NOT EXISTS test (f1 int primary key, f2 BIGINT)");
        stmt.executeUpdate("INSERT INTO test(f1, f2) VALUES(1, 1)");
        stmt.executeUpdate("INSERT INTO test(f1, f2) VALUES(2, 2)");
        stmt.executeUpdate("DELETE FROM test");

        stmt.executeUpdate("INSERT INTO test(f1, f2) VALUES(1, 1)");
        stmt.executeUpdate("UPDATE test SET f2 = 2 WHERE f1 = 1");
        ResultSet rs = stmt.executeQuery("SELECT * FROM test WHERE f1 = 1");
        Assert.assertTrue(rs.next());
        System.out.println("f1=" + rs.getInt(1) + " f2=" + rs.getLong(2));
        Assert.assertFalse(rs.next());
        rs.close();
        stmt.executeUpdate("DELETE FROM test WHERE f1 = 1");
        rs = stmt.executeQuery("SELECT * FROM test");
        Assert.assertFalse(rs.next());
        rs.close();
        stmt.executeUpdate("DROP TABLE IF EXISTS test");
        stmt.close();
        conn.close();
    }
}
