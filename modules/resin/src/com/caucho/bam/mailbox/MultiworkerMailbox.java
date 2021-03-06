/*
 * Copyright (c) 1998-2011 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.bam.mailbox;

import java.io.Closeable;
import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.caucho.bam.BamError;
import com.caucho.bam.BamException;
import com.caucho.bam.RemoteConnectionFailedException;
import com.caucho.bam.broker.Broker;
import com.caucho.bam.packet.Message;
import com.caucho.bam.packet.MessageError;
import com.caucho.bam.packet.Packet;
import com.caucho.bam.packet.QueryError;
import com.caucho.bam.packet.Query;
import com.caucho.bam.packet.QueryResult;
import com.caucho.bam.stream.MessageStream;
import com.caucho.lifecycle.Lifecycle;
import com.caucho.util.L10N;

/**
 * mailbox for BAM messages waiting to be sent to the Actor.
 */
public class MultiworkerMailbox implements Mailbox, Closeable
{
  private static final L10N L = new L10N(MultiworkerMailbox.class);
  private static final Logger log
    = Logger.getLogger(MultiworkerMailbox.class.getName());

  private final String _name;
  private final String _address;
  private final Broker _broker;
  private final MessageStream _actorStream;

  private final MailboxWorker []_workers;
  private final MailboxQueue _queue;
  
  private final Lifecycle _lifecycle = new Lifecycle();

  public MultiworkerMailbox(MessageStream actorStream,
                            Broker broker,
                            int threadMax)
  {
    this(null, actorStream, broker, threadMax);
  }
  
  public MultiworkerMailbox(String address, 
                            MessageStream actorStream,
                            Broker broker,
                            int threadMax)
  {
    _address = address;
    
    if (broker == null)
      throw new NullPointerException(L.l("broker must not be null"));

    if (actorStream == null)
      throw new NullPointerException(L.l("actorStream must not be null"));

    _broker = broker;
    _actorStream = actorStream;
    
    if (_actorStream.getAddress() == null) {
      _name = _actorStream.getClass().getSimpleName();
    }
    else
      _name = _actorStream.getAddress();
    
    _workers = new MailboxWorker[threadMax];
    
    for (int i = 0; i < threadMax; i++) {
      _workers[i] = createWorker();
    }
    
    int maxDiscardSize = -1;
    int maxBlockSize = 1024;
    long expireTimeout = -1;

    _queue = new MailboxQueue(_name, maxDiscardSize, maxBlockSize, expireTimeout);
    
    _lifecycle.toActive();
  }
  
  protected MailboxWorker createWorker()
  {
    return new MailboxWorker(this);
  }
  
  public int getThreadMax()
  {
    return _workers.length;
  }

  /**
   * Returns the actor's address
   */
  @Override
  public String getAddress()
  {
    if (_address != null)
      return _address;
    else
      return _actorStream.getAddress();
  }

  /**
   * Returns true if a message is available.
   */
  public boolean isPacketAvailable()
  {
    return ! _queue.isEmpty();
  }
  
  /**
   * Returns the stream back to the link for error packets
   */
  @Override
  public Broker getBroker()
  {
    return _broker;
  }

  @Override
  public MessageStream getActorStream()
  {
    return _actorStream;
  }

  /**
   * Sends a message
   */
  @Override
  public void message(String to, String from, Serializable value)
  {
    enqueue(new Message(to, from, value));
  }

  /**
   * Sends a message
   */
  @Override
  public void messageError(String to,
                               String from,
                               Serializable value,
                               BamError error)
  {
    enqueue(new MessageError(to, from, value, error));
  }

  /**
   * Query an entity
   */
  @Override
  public void query(long id,
                       String to,
                       String from,
                       Serializable query)
  {
    if (! _lifecycle.isActive()) {
      BamException exn = new RemoteConnectionFailedException(L.l("{0} is closed",
                                                                 this));
      exn.fillInStackTrace();
      
      getBroker().queryError(id, from, to, query,
                             BamError.create(exn));
      return;
    }

    enqueue(new Query(id, to, from, query));
  }

  /**
   * Query an entity
   */
  @Override
  public void queryResult(long id,
                          String to,
                          String from,
                          Serializable value)
  {
    enqueue(new QueryResult(id, to, from, value));
  }

  /**
   * Query an entity
   */
  @Override
  public void queryError(long id,
                         String to,
                         String from,
                         Serializable query,
                         BamError error)
  {
    enqueue(new QueryError(id, to, from, query, error));
  }

  protected final void enqueue(Packet packet)
  {
    if (! _lifecycle.isActive())
      throw new IllegalStateException(L.l("{0} cannot accept packets because it's no longer active",
                                          this));
    
    if (log.isLoggable(Level.FINEST)) {
      int size = _queue.getSize();
      log.finest(this + " enqueue(" + size + ") " + packet);
    }

    _queue.enqueue(packet);

    /*
    if (_dequeueCount.get() > 0)
      return;
    */

    wakeConsumer(packet);

    /*
    if (Alarm.isTest()) {
      // wait a millisecond for the dequeue to avoid spawing extra
      // processing threads
      packet.waitForDequeue(10);
    }
    */
  }

  private void wakeConsumer(Packet packet)
  {
    for (MailboxWorker worker : _workers) {
      boolean isRunning = worker.isRunning();
      
      worker.wake();
      
      if (! isRunning)
        return;
    }
  }

  /**
   * Dispatches the packet to the stream
   */
  protected void dispatch(Packet packet)
  {
    packet.dispatch(getActorStream(), _broker);
  }
  
  protected Packet dequeue()
  {
    return _queue.dequeue();
  }

  @Override
  public void close()
  {
    _lifecycle.toStop();

    for (MailboxWorker worker : _workers) {
      worker.wake();
    }
    
    long expires = getCurrentTimeActual() + 2000;
    
    while (! _queue.isEmpty()
           && getCurrentTimeActual() < expires) {
      try {
        Thread.sleep(100);
      } catch (Exception e) {
        
      }
    }

    for (MailboxWorker worker : _workers) {
      worker.destroy();
    }
    
    _lifecycle.toDestroy();
  }
  
  protected long getCurrentTimeActual()
  {
    return System.currentTimeMillis();
  }

  @Override
  public boolean isClosed()
  {
    return _lifecycle.isDestroying() || _broker.isClosed();
  }

  @Override
  public String toString()
  {
    return getClass().getSimpleName() + "[" + _name + "]";
  }
}
