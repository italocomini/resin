/*
 * Copyright (c) 1998-2004 Caucho Technology -- all rights reserved
 *
 * Caucho Technology permits modification and use of this file in
 * source and binary form ("the Software") subject to the Caucho
 * Developer Source License 1.1 ("the License") which accompanies
 * this file.  The License is also available at
 *   http://www.caucho.com/download/cdsl1-1.xtp
 *
 * In addition to the terms of the License, the following conditions
 * must be met:
 *
 * 1. Each copy or derived work of the Software must preserve the copyright
 *    notice and this notice unmodified.
 *
 * 2. Each copy of the Software in source or binary form must include 
 *    an unmodified copy of the License in a plain ASCII text file named
 *    LICENSE.
 *
 * 3. Caucho reserves all rights to its names, trademarks and logos.
 *    In particular, the names "Resin" and "Caucho" are trademarks of
 *    Caucho and may not be used to endorse products derived from
 *    this software.  "Resin" and "Caucho" may not appear in the names
 *    of products derived from this software.
 *
 * This Software is provided "AS IS," without a warranty of any kind. 
 * ALL EXPRESS OR IMPLIED REPRESENTATIONS AND WARRANTIES, INCLUDING ANY
 * IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE
 * OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED.
 *
 * CAUCHO TECHNOLOGY AND ITS LICENSORS SHALL NOT BE LIABLE FOR ANY DAMAGES
 * SUFFERED BY LICENSEE OR ANY THIRD PARTY AS A RESULT OF USING OR
 * DISTRIBUTING SOFTWARE. IN NO EVENT WILL CAUCHO OR ITS LICENSORS BE LIABLE
 * FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL,
 * CONSEQUENTIAL, INCIDENTAL OR PUNITIVE DAMAGES, HOWEVER CAUSED AND
 * REGARDLESS OF THE THEORY OF LIABILITY, ARISING OUT OF THE USE OF OR
 * INABILITY TO USE SOFTWARE, EVEN IF HE HAS BEEN ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGES.      
 *
 * @author Scott Ferguson
 */

package com.caucho.server.cluster;

import java.io.*;
import java.util.*;
import java.util.logging.*;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.naming.*;

import javax.sql.DataSource;

import com.caucho.config.ConfigException;

import com.caucho.db.jdbc.DataSourceImpl;

import com.caucho.loader.Environment;
import com.caucho.loader.EnvironmentLocal;

import com.caucho.util.L10N;
import com.caucho.util.Log;
import com.caucho.util.Alarm;
import com.caucho.util.CharBuffer;
import com.caucho.util.FreeList;

import com.caucho.vfs.Path;
import com.caucho.vfs.ReadStream;
import com.caucho.vfs.WriteStream;
import com.caucho.vfs.TempBuffer;
import com.caucho.vfs.TempStream;


/**
 * Manages the backing for the file store.
 */
public class FileBacking {
  private static final L10N L = new L10N(FileBacking.class);
  private static final Logger log = Log.open(FileBacking.class);
  
  private FreeList<ClusterConnection> _freeConn
    = new FreeList<ClusterConnection>(32);
  
  private String _name;
  
  private Path _path;

  private DataSource _dataSource;

  private String _tableName;
  private String _loadQuery;
  private String _updateQuery;
  private String _accessQuery;
  private String _setExpiresQuery;
  private String _insertQuery;
  private String _invalidateQuery;
  private String _timeoutQuery;
  private String _dumpQuery;

  /**
   * Returns the path to the directory.
   */
  public Path getPath()
  {
    return _path;
  }

  /**
   * Sets the path to the saved file.
   */
  public void setPath(Path path)
  {
    _path = path;
  }

  /**
   * Sets the table name
   */
  public void setTableName(String table)
  {
    _tableName = table;
  }

  public boolean init(int clusterLength)
    throws Exception
  {
    if (_path == null)
      throw new ConfigException(L.l("file-backing needs path."));
    
    if (_tableName == null)
      throw new ConfigException(L.l("file-backing needs tableName."));

    int length = clusterLength;

    if (length <= 0)
      length = 1;

    int backupLength = length - 1;
    if (backupLength <= 0)
      backupLength = 1;
    
    _loadQuery = "SELECT access_time,data FROM " + _tableName + " WHERE id=?";
    _insertQuery = ("INSERT into " + _tableName + " (id,data,mod_time,access_time,expire_interval,owner,backup) " +
		    "VALUES(?,?,?,?,?,?,?)");
    _updateQuery = "UPDATE " + _tableName + " SET data=?, mod_time=?, access_time=? WHERE id=?";
    _accessQuery = "UPDATE " + _tableName + " SET access_time=? WHERE id=?";
    _setExpiresQuery = "UPDATE " + _tableName + " SET expire_interval=? WHERE id=?";
    _invalidateQuery = "DELETE FROM " + _tableName + " WHERE id=?";

    // access window is 1/4 the expire interval
    _timeoutQuery = "DELETE FROM " + _tableName + " WHERE access_time + 5 * expire_interval / 4 < ?";

    _dumpQuery = ("SELECT id, expire_interval, data FROM " + _tableName +
		  " WHERE ? <= mod_time AND " +
		  "   (?=owner % " + length + " OR " +
		  "    ?=((owner + backup % " + backupLength + " + 1) % " + length + "))");

    try {
      _path.mkdirs();
    } catch (IOException e) {
    }

    DataSourceImpl dataSource = new DataSourceImpl();
    dataSource.setPath(_path);
    dataSource.setRemoveOnError(true);
    dataSource.init();
    
    _dataSource = dataSource;

    initDatabase();

    return true;
  }

  /**
   * Returns the data source.
   */
  public DataSource getDataSource()
  {
    return _dataSource;
  }

  /**
   * Create the database, initializing if necessary.
   */
  private void initDatabase()
    throws Exception
  {
    Connection conn = _dataSource.getConnection();

    try {
      Statement stmt = conn.createStatement();
      
      boolean hasDatabase = false;

      try {
	String sql = "SELECT expire_interval FROM " + _tableName + " WHERE 1=0";

	ResultSet rs = stmt.executeQuery(sql);
	rs.next();
	rs.close();

	return;
      } catch (Throwable e) {
	log.finer(e.toString());
      }

      try {
	stmt.executeQuery("DROP TABLE " + _tableName);
      } catch (Throwable e) {
	log.log(Level.FINEST, e.toString(), e);
      }

      String sql = ("CREATE TABLE " + _tableName + "(\n" +
		    "  id VARCHAR(128) PRIMARY KEY,\n" +
		    "  data BLOB,\n" +
		    "  expire_interval INTEGER,\n" +
		    "  access_time INTEGER,\n" +
		    "  mod_time INTEGER,\n" +
		    "  mod_count BIGINT,\n" +
		    "  owner INTEGER,\n" +
		    "  backup INTEGER)");

      log.fine(sql);

      stmt.executeUpdate(sql);
    } finally {
      conn.close();
    }
  }

  public long start()
    throws Exception
  {
    long delta = - Alarm.getCurrentTime();

    Connection conn = null;
    try {
      conn = _dataSource.getConnection();
      
      Statement stmt = conn.createStatement();

      String sql = "SELECT MAX(access_time) FROM " + _tableName;

      ResultSet rs = stmt.executeQuery(sql);

      if (rs.next())
	delta = rs.getInt(1) * 60000L - Alarm.getCurrentTime();
    } finally {
      if (conn != null)
	conn.close();
    }

    return delta;
  }

  /**
   * Clears the old objects.
   */
  public void clearOldObjects(long maxIdleTime)
    throws SQLException
  {
    Connection conn = null;

    try {
      if (maxIdleTime > 0) {
	conn = _dataSource.getConnection();

	PreparedStatement pstmt = conn.prepareStatement(_timeoutQuery);
  
        long now = Alarm.getCurrentTime();
        int nowMinute = (int) (now / 60000L);
	
	pstmt.setInt(1, nowMinute);

        int count = pstmt.executeUpdate();

	if (count > 0)
	  log.fine(this + " purged " + count + " old sessions");

	pstmt.close();
      }
    } finally {
      if (conn != null)
	conn.close();
    }
  }

  /**
   * Load the session from the jdbc store.
   *
   * @param session the session to fill.
   *
   * @return true if the load was valid.
   */
  public boolean loadSelf(ClusterObject clusterObj, Object obj)
    throws Exception
  {
    String uniqueId = clusterObj.getUniqueId();

    ClusterConnection conn = getConnection();
    try {
      PreparedStatement stmt = conn.prepareLoad();
      stmt.setString(1, uniqueId);

      ResultSet rs = stmt.executeQuery();
      boolean validLoad = false;

      if (rs.next()) {
	long accessTime = rs.getInt(1) * 60000L;
	
        InputStream is = rs.getBinaryStream(2);

        if (log.isLoggable(Level.FINE))
          log.fine("load local object: " + uniqueId);
      
        validLoad = clusterObj.load(is, obj);

	if (validLoad)
	  clusterObj.setAccessTime(accessTime);

        is.close();
      }
      else if (log.isLoggable(Level.FINE))
        log.fine("no local object loaded for " + uniqueId);

      rs.close();

      return validLoad;
    } finally {
      conn.close();
    }
  }

  /**
   * Updates the object's access time.
   *
   * @param obj the object to store.
   */
  public void updateAccess(String uniqueId)
    throws Exception
  {
    ClusterConnection conn = getConnection();
    
    try {
      PreparedStatement stmt = conn.prepareAccess();

      long now = Alarm.getCurrentTime();
      int nowMinutes = (int) (now / 60000L);
      stmt.setInt(1, nowMinutes);
      stmt.setString(2, uniqueId);

      int count = stmt.executeUpdate();

      if (count > 0) {
	if (log.isLoggable(Level.FINE)) 
	  log.fine("access cluster: " + uniqueId);
	return;
      }
    } finally {
      conn.close();
    }
  }

  /**
   * Sets the object's expire_interval.
   *
   * @param obj the object to store.
   */
  public void setExpireInterval(String uniqueId, long expireInterval)
    throws Exception
  {
    ClusterConnection conn = getConnection();
    
    try {
      PreparedStatement stmt = conn.prepareSetExpireInterval();

      int expireMinutes = (int) (expireInterval / 60000L);
      stmt.setInt(1, expireMinutes);
      stmt.setString(2, uniqueId);

      int count = stmt.executeUpdate();

      if (count > 0) {
	if (log.isLoggable(Level.FINE)) 
	  log.fine("set expire interval: " + uniqueId + " " + expireInterval);
	return;
      }
    } finally {
      conn.close();
    }
  }

  /**
   * Removes the named object from the store.
   */
  public void remove(String uniqueId)
    throws Exception
  {
    ClusterConnection conn = getConnection();
    
    try {
      PreparedStatement pstmt = conn.prepareInvalidate();
      pstmt.setString(1, uniqueId);

      int count = pstmt.executeUpdate();
      
      if (log.isLoggable(Level.FINE))
        log.fine("invalidate: " + uniqueId);
    } finally {
      conn.close();
    }
  }

  /**
   * Reads from the store.
   */
  public long read(String uniqueId, WriteStream os)
    throws IOException
  {
    Connection conn = null;
    try {
      conn = _dataSource.getConnection();

      PreparedStatement pstmt = conn.prepareStatement(_loadQuery);
      pstmt.setString(1, uniqueId);

      ResultSet rs = pstmt.executeQuery();
      if (rs.next()) {
	long accessTime = rs.getInt(1) * 60000L;
	
	InputStream is = rs.getBinaryStream(2);

	os.writeStream(is);

	is.close();

	return accessTime;
      }
    } catch (SQLException e) {
      log.log(Level.FINE, e.toString(), e);
    } finally {
      try {
	if (conn != null)
	  conn.close();
      } catch (SQLException e) {
      }
    }

    return -1;
  }

  /**
   * Stores the cluster object on the local store.
   *
   * @param uniqueId the object's unique id.
   * @param id the input stream to the serialized object
   * @param length the length object the serialized object
   * @param expireInterval how long the object lives w/o access
   */
  public void storeSelf(String uniqueId,
			ReadStream is, int length,
			long expireInterval,
			int owner, int backup)
  {
    ClusterConnection conn = null;

    try {
      conn = getConnection();
      // Try to update first, and insert if fail.
      // The binary stream can be reused because it won't actually be
      // read on a failure

      if (storeSelfUpdate(conn, uniqueId, is, length)) {
      }
      else if (storeSelfInsert(conn, uniqueId, is, length, expireInterval,
			       owner, backup)) {
      }
      else if (! storeSelfUpdate(conn, uniqueId, is, length)) {
	// The second update is for the rare case where
	// two threads try to update the database simultaneously
	
	log.warning(L.l("Can't store session {0}", uniqueId));
      }
    } catch (SQLException e) {
      log.log(Level.FINE, e.toString(), e);
    } finally {
      if (conn != null)
	conn.close();
    }
  }
  
  /**
   * Stores the cluster object on the local store using an update query.
   *
   * @param conn the database connection
   * @param uniqueId the object's unique id.
   * @param id the input stream to the serialized object
   * @param length the length object the serialized object
   */
  private boolean storeSelfUpdate(ClusterConnection conn, String uniqueId,
				  ReadStream is, int length)
  {
    try {
      PreparedStatement stmt = conn.prepareUpdate();
      stmt.setBinaryStream(1, is, length);

      long now = Alarm.getCurrentTime();
      int nowMinutes = (int) (now / 60000L);
      stmt.setInt(2, nowMinutes);
      stmt.setInt(3, nowMinutes);
      stmt.setString(4, uniqueId);

      int count = stmt.executeUpdate();
        
      if (count > 0) {
	if (log.isLoggable(Level.FINE)) 
	  log.fine("update cluster: " + uniqueId + " length:" + length);
	  
	return true;
      }
    } catch (SQLException e) {
      log.log(Level.FINER, e.toString(), e);
    }

    return false;
  }
  
  private boolean storeSelfInsert(ClusterConnection conn, String uniqueId,
				  ReadStream is, int length,
				  long expireInterval,
				  int owner, int backup)
  {
    try {
      PreparedStatement stmt = conn.prepareInsert();
        
      stmt.setString(1, uniqueId);

      stmt.setBinaryStream(2, is, length);
      
      int nowMinutes = (int) (Alarm.getCurrentTime() / 60000L);
      
      stmt.setInt(3, nowMinutes);
      stmt.setInt(4, nowMinutes);
      stmt.setInt(5, (int) (expireInterval / 60000L));

      stmt.setInt(6, owner);
      stmt.setInt(7, backup);
      
      stmt.executeUpdate();
        
      if (log.isLoggable(Level.FINE))
	log.fine("insert cluster: " + uniqueId + " length:" + length);

      return true;
    } catch (SQLException e) {
      log.log(Level.FINE, e.toString(), e);
    }

    return false;
  }

  public void destroy()
  {
    _dataSource = null;
    _freeConn = null;
  }

  private ClusterConnection getConnection()
    throws SQLException
  {
    ClusterConnection cConn = _freeConn.allocate();

    if (cConn == null) {
      Connection conn = _dataSource.getConnection();
      cConn = new ClusterConnection(conn);
    }

    return cConn;
  }

  public String serverNameToTableName(String serverName)
  {
    if (serverName == null)
      return "srun";
    
    StringBuilder cb = new StringBuilder();
    cb.append("srun_");
    
    for (int i = 0; i < serverName.length(); i++) {
      char ch = serverName.charAt(i);

      if ('a' <= ch && ch <= 'z') {
	cb.append(ch);
      }
      else if ('A' <= ch && ch <= 'Z') {
	cb.append(ch);
      }
      else if ('0' <= ch && ch <= '9') {
	cb.append(ch);
      }
      else if (ch == '_') {
	cb.append(ch);
      }
      else
	cb.append('_');
    }

    return cb.toString();
  }

  public String toString()
  {
    return "ClusterStore[" + _name + "]";
  }

  class ClusterConnection {
    private Connection _conn;
    
    private PreparedStatement _loadStatement;
    private PreparedStatement _updateStatement;
    private PreparedStatement _insertStatement;
    private PreparedStatement _accessStatement;
    private PreparedStatement _setExpiresStatement;
    private PreparedStatement _invalidateStatement;
    private PreparedStatement _timeoutStatement;

    ClusterConnection(Connection conn)
    {
      _conn = conn;
    }

    PreparedStatement prepareLoad()
      throws SQLException
    {
      if (_loadStatement == null)
	_loadStatement = _conn.prepareStatement(_loadQuery);

      return _loadStatement;
    }

    PreparedStatement prepareUpdate()
      throws SQLException
    {
      if (_updateStatement == null)
	_updateStatement = _conn.prepareStatement(_updateQuery);

      return _updateStatement;
    }

    PreparedStatement prepareInsert()
      throws SQLException
    {
      if (_insertStatement == null)
	_insertStatement = _conn.prepareStatement(_insertQuery);

      return _insertStatement;
    }

    PreparedStatement prepareAccess()
      throws SQLException
    {
      if (_accessStatement == null)
	_accessStatement = _conn.prepareStatement(_accessQuery);

      return _accessStatement;
    }

    PreparedStatement prepareSetExpireInterval()
      throws SQLException
    {
      if (_setExpiresStatement == null)
	_setExpiresStatement = _conn.prepareStatement(_setExpiresQuery);

      return _setExpiresStatement;
    }

    PreparedStatement prepareInvalidate()
      throws SQLException
    {
      if (_invalidateStatement == null)
	_invalidateStatement = _conn.prepareStatement(_invalidateQuery);

      return _invalidateStatement;
    }

    void close()
    {
      _freeConn.free(this);
    }
  }
}
