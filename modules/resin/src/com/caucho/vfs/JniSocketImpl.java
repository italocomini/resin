/*
 * Copyright (c) 1998-2011 Caucho Technology -- all rights reserved
 *
 * @author Scott Ferguson
 */

package com.caucho.vfs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.caucho.inject.Module;
import com.caucho.util.Alarm;
import com.caucho.util.JniTroubleshoot;
import com.caucho.util.L10N;

/**
 * Abstract socket to handle both normal sockets and bin/resin sockets.
 */
@Module
public final class JniSocketImpl extends QSocket {
  private static final L10N L = new L10N(JniSocketImpl.class);
  
  private static final Logger log
    = Logger.getLogger(JniSocketImpl.class.getName());

  private static final JniTroubleshoot _jniTroubleshoot;

  private long _fd;
  private JniStream _stream;

  private final byte []_localAddrBuffer = new byte[16];
  private final char []_localAddrCharBuffer = new char[256];

  private int _localAddrLength;
  private String _localName;
  private InetAddress _localAddr;

  private int _localPort;

  private final byte []_remoteAddrBuffer = new byte[16];
  private final char []_remoteAddrCharBuffer = new char[256];

  private int _remoteAddrLength;
  private String _remoteName;
  private InetAddress _remoteAddr;

  private int _remotePort;

  private boolean _isSecure;

  private Object _readLock = new Object();
  private Object _writeLock = new Object();
  
  private long _socketTimeout;
  private long _requestExpireTime;
  
  private final AtomicBoolean _isClosed = new AtomicBoolean();
  
  // private ByteBuffer _byteBuffer = ByteBuffer.allocate(TempBuffer.SIZE);
  // private ByteBuffer _byteBuffer = ByteBuffer.wrap(new byte[TempBuffer.SIZE]);
  // private ByteBuffer _byteBuffer = createByteBuffer(TempBuffer.SIZE);

  public JniSocketImpl()
  {
    _fd = nativeAllocate();
  }

  public static boolean isEnabled()
  {
    return _jniTroubleshoot.isEnabled();
  }

  public static String getInitMessage()
  {
    if (! _jniTroubleshoot.isEnabled())
      return _jniTroubleshoot.getMessage();
    else
      return null;
  }
  
  public static JniSocketImpl connect(String host, int port)
    throws IOException
  {
    JniSocketImpl socket = new JniSocketImpl();
    
    if (socket.connectImpl(host, port))
      return socket;
    else {
      socket.close();
      
      return null;
    }
  }

  /**
   * Creates the new server socket.
   */
  public boolean connectImpl(String host, int port)
    throws IOException
  {
    _socketTimeout = 10000;
    
    _isClosed.set(false);
    
    synchronized (_writeLock) {
      return nativeConnect(_fd, host, port);
    }
  }

  boolean accept(long serverSocketFd, long socketTimeout)
  {
    _localName = null;
    _localAddr = null;
    _localAddrLength = 0;

    _remoteName = null;
    _remoteAddr = null;
    _remoteAddrLength = 0;
    
    _socketTimeout = socketTimeout;
    _requestExpireTime = 0;

    _isSecure = false;
    _isClosed.set(false);

    synchronized (_writeLock) {
      // initialize fields from the _fd
      return nativeAccept(serverSocketFd, _fd, _localAddrBuffer, _remoteAddrBuffer);
    }
  }

  public long getFd()
  {
    return _fd;
  }

  public int getNativeFd()
  {
    return getNativeFd(_fd);
  }

  /**
   * Returns the server port that accepted the request.
   */
  @Override
  public int getLocalPort()
  {
    return _localPort;

    // return getLocalPort(_fd);
  }

  /**
   * Returns the remote client's host name.
   */
  @Override
  public String getRemoteHost()
  {
    if (_remoteName == null) {
      byte []remoteAddrBuffer = _remoteAddrBuffer;
      char []remoteAddrCharBuffer = _remoteAddrCharBuffer;

      if (_remoteAddrLength <= 0) {
        _remoteAddrLength = createIpAddress(remoteAddrBuffer,
                                            remoteAddrCharBuffer);
      }

      _remoteName = new String(remoteAddrCharBuffer, 0, _remoteAddrLength);
    }

    return _remoteName;
  }

  /**
   * Returns the remote client's inet address.
   */
  @Override
  public InetAddress getRemoteAddress()
  {
    if (_remoteAddr == null) {
      try {
        _remoteAddr = InetAddress.getByName(getRemoteHost());
      } catch (Exception e) {
        log.log(Level.FINE, e.toString(), e);
      }
    }

    return _remoteAddr;
  }

  /**
   * Returns the remote client's inet address.
   */
  @Override
  public int getRemoteAddress(byte []buffer, int offset, int length)
  {
    int len = _remoteAddrLength;

    if (len <= 0) {
      len = _remoteAddrLength = createIpAddress(_remoteAddrBuffer,
                                                _remoteAddrCharBuffer);
    }

    char []charBuffer = _remoteAddrCharBuffer;

    for (int i = len - 1; i >= 0; i--) {
      buffer[offset + i] = (byte) charBuffer[i];
    }

    return len;
  }

  /**
   * Returns the remote client's inet address.
   */
  @Override
  public byte []getRemoteIP()
  {
    return _remoteAddrBuffer;
  }

  /**
   * Returns the remote client's port.
   */
  @Override
  public int getRemotePort()
  {
    return _remotePort;

    // return getRemotePort(_fd);
  }

  /**
   * Returns the local server's host name.
   */
  @Override
  public String getLocalHost()
  {
    if (_localName == null) {
      byte []localAddrBuffer = _localAddrBuffer;
      char []localAddrCharBuffer = _localAddrCharBuffer;

      if (_localAddrLength <= 0) {
        _localAddrLength = createIpAddress(localAddrBuffer,
                                           localAddrCharBuffer);
      }

      _localName = new String(localAddrCharBuffer, 0, _localAddrLength);
    }

    return _localName;
  }

  /**
   * Returns the local server's inet address.
   */
  @Override
  public InetAddress getLocalAddress()
  {
    if (_localAddr == null) {
      try {
        _localAddr = InetAddress.getByName(getLocalHost());
      } catch (Exception e) {
        log.log(Level.FINE, e.toString(), e);
      }
    }

    return _localAddr;
  }

  /**
   * Returns the local server's inet address.
   */
  public int getLocalAddress(byte []buffer, int offset, int length)
  {
    System.arraycopy(_localAddrBuffer, 0, buffer, offset, _localAddrLength);

    return _localAddrLength;
  }

  /**
   * Set true for secure.
   */
  public void setSecure(boolean isSecure)
  {
    _isSecure = isSecure;
  }

  /**
   * Returns true if the connection is secure.
   */
  @Override
  public final boolean isSecure()
  {
    // return isSecure(_fd);

    return _isSecure;
  }

  /**
   * Returns the cipher for an ssl connection.
   */
  @Override
  public String getCipherSuite()
  {
    synchronized (this) {
      return getCipher(_fd);
    }
  }

  /**
   * Returns the number of bits in the cipher for an ssl connection.
   */
  @Override
  public int getCipherBits()
  {
    return getCipherBits(_fd);
  }

  /**
   * Returns the client certificate.
   */
  @Override
  public X509Certificate getClientCertificate()
    throws java.security.cert.CertificateException
  {
    TempBuffer tb = TempBuffer.allocate();
    byte []buffer = tb.getBuffer();
    int len = getClientCertificate(_fd, buffer, 0, buffer.length);
    X509Certificate cert = null;

    if (len > 0 && len < buffer.length) {
      try {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        InputStream is = new ByteArrayInputStream(buffer, 0, len);
        cert = (X509Certificate) cf.generateCertificate(is);
        is.close();
      } catch (IOException e) {
        return null;
      }
    }

    TempBuffer.free(tb);
    tb = null;

    return cert;
  }
  
  /**
   * Sets the expire time
   */
  @Override
  public void setRequestExpireTime(long expireTime)
  {
    _requestExpireTime = expireTime;
  }

  /**
   * Read non-blocking
   */
  @Override
  public boolean isEof()
  {
    synchronized (_readLock) {
      return nativeIsEof(_fd);
    }
  }

  /**
   * Reads from the socket.
   */
  public int read(byte []buffer, int offset, int length, long timeout)
    throws IOException
  {
    if (length == 0)
      throw new IllegalArgumentException();
    
    long requestExpireTime = _requestExpireTime;

    if (requestExpireTime > 0 && requestExpireTime < Alarm.getCurrentTime()) {
      close();
      throw new ClientDisconnectException(L.l("{0}: request-timeout read",
                                              getRemoteAddress()));
    }
    
    synchronized (_readLock) {
      long now = Alarm.getCurrentTimeActual();
      
      long expires;
      
      // gap is because getCurrentTimeActual() isn't exact
      long gap = 20;
      
      if (timeout >= 0)
        expires = timeout + now - gap;
      else
        expires = _socketTimeout + now - gap;

      int result = 0;

      do {
        result = readNative(_fd, buffer, offset, length, timeout);
        
        now = Alarm.getCurrentTimeActual();
        
        timeout = expires - now;
      } while (result == JniStream.TIMEOUT_EXN && timeout > 0);
      
      return result;
    }
  }

  /**
   * Writes to the socket.
   */
  public int write(byte []buffer, int offset, int length, boolean isEnd)
    throws IOException
  {
    int result;
    
    long requestExpireTime = _requestExpireTime;
    
    if (requestExpireTime > 0 && requestExpireTime < Alarm.getCurrentTime()) {
      close();
      throw new ClientDisconnectException(L.l("{0}: request-timeout write",
                                              getRemoteAddress()));
    }
    
    synchronized (_writeLock) {
      long now = Alarm.getCurrentTimeActual();
      long expires = _socketTimeout + now;
      
      do {
        result = writeNative(_fd, buffer, offset, length);
        
        //byte []tempBuffer = _byteBuffer.array();
        //System.out.println("TEMP: " + tempBuffer);
        //System.arraycopy(buffer, offset, tempBuffer, 0, length);
        //_byteBuffer.position(0);
        //_byteBuffer.put(buffer, offset, length);
        //result = writeNativeNio(_fd, _byteBuffer, 0, length);
      } while (result == JniStream.TIMEOUT_EXN
               && Alarm.getCurrentTimeActual() < expires);
    }
    
    if (isEnd) {
      close();
    }
    return result;
  }

  /**
   * Flushes the socket.
   */
  public int flush()
    throws IOException
  {
    synchronized (_writeLock) {
      return flushNative(_fd);
    }
  }

  /**
   * Returns a stream impl for the socket encapsulating the
   * input and output stream.
   */
  @Override
  public StreamImpl getStream()
    throws IOException
  {
    if (_stream == null)
      _stream = new JniStream(this);

    _stream.init();

    return _stream;
  }

  public long getTotalReadBytes()
  {
    return (_stream == null) ? 0 : _stream.getTotalReadBytes();
  }

  public long getTotalWriteBytes()
  {
    return (_stream == null) ? 0 : _stream.getTotalWriteBytes();
  }

  private int createIpAddress(byte []address, char []buffer)
  {
    if (isIpv4(address)) {
      return createIpv4Address(address, 0, buffer, 0);
    }

    int offset = 0;
    boolean isZeroCompress = false;
    boolean isInZeroCompress = false;

    buffer[offset++] = '[';

    for (int i = 0; i < 16; i += 2) {
      int value = (address[i] & 0xff) * 256 + (address[i + 1] & 0xff);

      if (value == 0 && i != 14) {
        if (isInZeroCompress)
          continue;
        else if (! isZeroCompress) {
          isZeroCompress = true;
          isInZeroCompress = true;
          continue;
        }
      }

      if (isInZeroCompress) {
        isInZeroCompress = false;
        buffer[offset++] = ':';
        buffer[offset++] = ':';
      }
      else if (i != 0){
        buffer[offset++] = ':';
      }

      if (value == 0) {
        buffer[offset++] = '0';
        continue;
      }

      offset = writeHexDigit(buffer, offset, value >> 12);
      offset = writeHexDigit(buffer, offset, value >> 8);
      offset = writeHexDigit(buffer, offset, value >> 4);
      offset = writeHexDigit(buffer, offset, value);
    }

    buffer[offset++] = ']';

    return offset;
  }

  private boolean isIpv4(byte []buffer)
  {
    if (buffer[10] != (byte) 0xff || buffer[11] != (byte) 0xff)
      return false;

    for (int i = 0; i < 10; i++) {
      if (buffer[i] != 0)
        return false;
    }

    return true;
  }

  private int writeHexDigit(char []buffer, int offset, int value)
  {
    if (value == 0)
      return offset;

    value = value & 0xf;

    if (value < 10)
      buffer[offset++] = (char) ('0' + value);
    else
      buffer[offset++] = (char) ('a' + value - 10);

    return offset;
  }

  private int createIpv4Address(byte []address, int addressOffset,
                                char []buffer, int bufferOffset)
  {
    int tailOffset = bufferOffset;

    for (int i = 12; i < 16; i++) {
      if (i > 12)
        buffer[tailOffset++] = '.';

      int digit = address[addressOffset + i] & 0xff;
      int d1 = digit / 100;
      int d2 = digit / 10 % 10;
      int d3 = digit % 10;

      if (digit >= 100) {
        buffer[tailOffset++] = (char) ('0' + d1);
      }

      if (digit >= 10) {
        buffer[tailOffset++] = (char) ('0' + d2);
      }

      buffer[tailOffset++] = (char) ('0' + d3);
    }

    return tailOffset - bufferOffset;
  }

  /**
   * Returns true if closed.
   */
  public boolean isClosed()
  {
    return _isClosed.get();
  }

  /**
   * Closes the socket.
   *
   * XXX: potential sync issues
   */
  @Override
  public void forceShutdown()
  {
    // can't be locked because of shutdown
    nativeCloseFd(_fd);
  }

  /**
   * Closes the socket.
   */
  @Override
  public void close()
    throws IOException
  {
    if (_isClosed.getAndSet(true))
      return;
    
    if (_stream != null)
      _stream.close();

    // XXX: can't be locked because of shutdown
    nativeClose(_fd);
  }

  @Override
  protected void finalize()
    throws Throwable
  {
    long fd = _fd;
    _fd = 0;
    
    try {
      super.finalize();

      nativeClose(fd);
    } catch (Throwable e) {
    }

    nativeFree(fd);
  }

  native int getNativeFd(long fd);

  native boolean nativeIsEof(long fd);

  private native boolean nativeAccept(long serverSocketFd,
                                      long socketfd,
                                      byte []localAddress,
                                      byte []remoteAddress);

  private native boolean nativeConnect(long socketfd,
                                       String host,
                                       int port);

  native String getCipher(long fd);

  native int getCipherBits(long fd);

  native int getClientCertificate(long fd,
                                  byte []buffer,
                                  int offset,
                                  int length);

  native int readNative(long fd, byte []buf, int offset, int length,
                        long timeout)
    throws IOException;

  private static native int writeNative(long fd, byte []buf, int offset, int length)
    throws IOException;

  /*
  private native int writeCloseNative(long fd,
                                      byte []buf, int offset, int length)
    throws IOException;
    */
  native int writeNative2(long fd,
                          byte []buf1, int off1, int len1,
                          byte []buf2, int off2, int len2)
    throws IOException;

  /*
  native int writeNativeNio(long fd,
                            ByteBuffer byteBuffer, int offset, int length)
    throws IOException;
  native ByteBuffer createByteBuffer(int length);
*/

  native int flushNative(long fd) throws IOException;

  private native void nativeCloseFd(long fd);
  private native void nativeClose(long fd);

  native long nativeAllocate();

  native void nativeFree(long fd);

  public String toString()
  {
    return ("JniSocketImpl$" + System.identityHashCode(this)
            + "[" + _fd + ",fd=" + getNativeFd(_fd) + "]");
  }

  static {
    JniTroubleshoot jniTroubleshoot = null;

    try {
      System.loadLibrary("resin_os");

      jniTroubleshoot 
        = new JniTroubleshoot(JniSocketImpl.class, "resin_os");
    } 
    catch (Throwable e) {
      jniTroubleshoot 
        = new JniTroubleshoot(JniSocketImpl.class, "resin_os", e);
    }

    _jniTroubleshoot = jniTroubleshoot;
  }
}

